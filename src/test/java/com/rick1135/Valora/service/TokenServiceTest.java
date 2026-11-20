package com.rick1135.Valora.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.rick1135.Valora.config.RedisConfig;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.exception.InvalidTokenException;
import com.rick1135.Valora.exception.TokenGenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {
    private static final String SECRET = "test-secret";
    private static final String ISSUER = "valora-api";
    private static final String USER_INDEX_PREFIX = "auth:refresh:user:";

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = configuredService(redisTemplate);
    }

    @Test
    void generateTokenUsesUserIdAndTokenVersion() {
        User user = persistedUser();

        String token = tokenService.generateToken(user);
        var decoded = JWT.require(Algorithm.HMAC256(SECRET)).withIssuer(ISSUER).build().verify(token);

        assertThat(decoded.getSubject()).isEqualTo(user.getId().toString());
        assertThat(decoded.getClaim("tv").asLong()).isEqualTo(7L);
        assertThat(decoded.getExpiresAtAsInstant()).isAfter(Instant.now());
        assertThat(tokenService.validateAccessToken(token))
                .isEqualTo(new TokenService.VerifiedAccessToken(user.getId(), 7L));
        assertThat(tokenService.validateToken(token)).isEqualTo(user.getId().toString());
    }

    @Test
    void tokensRequirePersistedUserId() {
        User user = new User();
        assertThatThrownBy(() -> tokenService.generateToken(user)).isInstanceOf(TokenGenerationException.class);
        assertThatThrownBy(() -> tokenService.generateRefreshToken(user)).isInstanceOf(TokenGenerationException.class);
        verifyNoInteractions(redisTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "user@valora.dev", "1-2-3-4-5"})
    void verifyRejectsNonUuidSubject(String subject) {
        assertInvalidAccessToken(JWT.create().withSubject(subject).withClaim("tv", 0L)
                .withIssuer(ISSUER).sign(Algorithm.HMAC256(SECRET)));
    }

    @Test
    void verifyRejectsMissingNullAndNonintegerVersion() {
        var builder = JWT.create().withSubject(UUID.randomUUID().toString()).withIssuer(ISSUER);
        var algorithm = Algorithm.HMAC256(SECRET);

        assertInvalidAccessToken(builder.sign(algorithm));
        assertInvalidAccessToken(builder.withNullClaim("tv").sign(algorithm));
        assertInvalidAccessToken(builder.withClaim("tv", "0").sign(algorithm));
        assertInvalidAccessToken(builder.withClaim("tv", 0.5).sign(algorithm));
    }

    @Test
    void verifyRejectsExpiredWrongIssuerTamperedAndMalformedAccessTokens() {
        var builder = JWT.create().withSubject(UUID.randomUUID().toString()).withClaim("tv", 0L)
                .withIssuer(ISSUER).withExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        var algorithm = Algorithm.HMAC256(SECRET);

        assertInvalidAccessToken(builder.sign(algorithm));
        builder.withExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        assertInvalidAccessToken(builder.withIssuer("other-issuer").sign(algorithm));
        assertInvalidAccessToken(builder.withIssuer(ISSUER).sign(Algorithm.HMAC256("other-secret")));
        assertInvalidAccessToken("not-a-jwt");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankRefreshTokensDoNotAccessRedis(String token) {
        assertThatThrownBy(() -> tokenService.consumeRefreshTokenWithVersion(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(GlobalExceptionHandler.GENERIC_REFRESH_TOKEN_MESSAGE);

        tokenService.revokeRefreshToken(token);
        verifyNoInteractions(redisTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000-0000-0000-0000-000000000001", "bad-id:0",
            "00000000-0000-0000-0000-000000000001:bad-version", "00000000-0000-0000-0000-000000000001:"})
    void consumeRejectsLegacyAndMalformedPayloads(String payload) {
        when(redisTemplate.<String>execute(any(), any(org.springframework.data.redis.serializer.RedisSerializer.class), any(org.springframework.data.redis.serializer.RedisSerializer.class), anyList(), eq(USER_INDEX_PREFIX)))
                .thenReturn(payload);

        assertThatThrownBy(() -> tokenService.consumeRefreshTokenWithVersion("refresh-token"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(GlobalExceptionHandler.GENERIC_REFRESH_TOKEN_MESSAGE);
    }

    @Test
    void consumptionFailsClosedWhenRedisFails() {
        when(redisTemplate.<String>execute(any(), any(org.springframework.data.redis.serializer.RedisSerializer.class), any(org.springframework.data.redis.serializer.RedisSerializer.class), anyList(), eq(USER_INDEX_PREFIX)))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> tokenService.consumeRefreshTokenWithVersion("sensitive-refresh-token"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(GlobalExceptionHandler.GENERIC_REFRESH_TOKEN_MESSAGE)
                .hasMessageNotContaining("redis down")
                .hasMessageNotContaining("sensitive-refresh-token");
    }

    @Test
    void revokeAllPropagatesRedisFailure() {
        UUID userId = UUID.randomUUID();
        var failure = new IllegalStateException("redis down");
        when(redisTemplate.<Long>execute(any(), eq(List.of(USER_INDEX_PREFIX + userId))))
                .thenThrow(failure);

        assertThatThrownBy(() -> tokenService.revokeAllUserTokens(userId))
                .isInstanceOf(TokenGenerationException.class)
                .hasMessage("Erro ao revogar refresh tokens do usuario.")
                .hasCause(failure);
    }

    @Test
    void revokeSinglePropagatesRedisFailure() {
        var failure = new IllegalStateException("redis down");
        when(redisTemplate.<String>execute(any(), any(org.springframework.data.redis.serializer.RedisSerializer.class), any(org.springframework.data.redis.serializer.RedisSerializer.class), anyList(), eq(USER_INDEX_PREFIX)))
                .thenThrow(failure);

        assertThatThrownBy(() -> tokenService.revokeRefreshToken("refresh-token"))
                .isInstanceOf(TokenGenerationException.class)
                .hasMessage("Erro ao revogar refresh token.")
                .hasCause(failure);
    }

    @Test
    void redisScriptsStoreConsumeAndRevokeRefreshTokensAtomically() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Redis test requires Docker");
        try (var redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379)) {
            redis.start();
            var connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
            connectionFactory.afterPropertiesSet();
            try {
                var service = configuredService(new RedisConfig().redisTemplate(connectionFactory));
                var strings = new StringRedisTemplate(connectionFactory);
                User user = persistedUser();
                User other = persistedUser();
                String userIndex = USER_INDEX_PREFIX + user.getId();

                String consumed = service.generateRefreshToken(user);
                String singleRevoked = service.generateRefreshToken(user);
                String allRevoked = service.generateRefreshToken(user);
                String otherToken = service.generateRefreshToken(other);
                String consumedKey = redisKey(consumed);
                assertThat(strings.opsForValue().get(consumedKey)).isEqualTo(user.getId() + ":7");
                assertThat(strings.opsForSet().members(userIndex))
                        .containsExactlyInAnyOrder(consumedKey, redisKey(singleRevoked), redisKey(allRevoked));
                assertThat(strings.getExpire(consumedKey)).isPositive().isLessThanOrEqualTo(2_592_000L);
                assertThat(strings.getExpire(userIndex)).isPositive().isLessThanOrEqualTo(2_592_000L);

                assertOneConcurrentConsumer(service, consumed, new TokenService.ConsumedRefreshToken(user.getId(), 7L));
                assertThat(strings.hasKey(consumedKey)).isFalse();
                assertThat(strings.opsForSet().isMember(userIndex, consumedKey)).isFalse();

                service.revokeRefreshToken(singleRevoked);
                service.revokeRefreshToken(singleRevoked);
                assertThat(strings.hasKey(redisKey(singleRevoked))).isFalse();
                assertThat(strings.opsForSet().members(userIndex)).containsExactly(redisKey(allRevoked));

                service.revokeAllUserTokens(user.getId());
                service.revokeAllUserTokens(user.getId());
                assertThat(strings.hasKey(redisKey(allRevoked))).isFalse();
                assertThat(strings.hasKey(userIndex)).isFalse();
                assertThat(service.consumeRefreshToken(otherToken)).isEqualTo(other.getId());

                assertConcurrentStoreAndRevocationKeepIndexConsistent(service, strings, user, userIndex);
            } finally {
                connectionFactory.destroy();
            }
        }
    }

    private void assertConcurrentStoreAndRevocationKeepIndexConsistent(
            TokenService service, StringRedisTemplate strings, User user, String userIndex) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var store = executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return service.generateRefreshToken(user);
            });
            var revoke = executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                service.revokeAllUserTokens(user.getId());
                return null;
            });

            start.countDown();
            String key = redisKey(store.get(10, TimeUnit.SECONDS));
            revoke.get(10, TimeUnit.SECONDS);
            assertThat(strings.hasKey(key)).isEqualTo(strings.opsForSet().isMember(userIndex, key));
        }
    }

    private void assertOneConcurrentConsumer(TokenService service, String token,
                                             TokenService.ConsumedRefreshToken expected) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<Optional<TokenService.ConsumedRefreshToken>> consume = () -> {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                return Optional.of(service.consumeRefreshTokenWithVersion(token));
            } catch (InvalidTokenException exception) {
                assertThat(exception).hasMessage(GlobalExceptionHandler.GENERIC_REFRESH_TOKEN_MESSAGE);
                return Optional.empty();
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(consume);
            var second = executor.submit(consume);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Optional.of(expected), Optional.empty());
        }
    }

    private TokenService configuredService(RedisTemplate<String, Object> template) {
        TokenService service = new TokenService(Clock.systemUTC(), template);
        ReflectionTestUtils.setField(service, "secret", SECRET);
        ReflectionTestUtils.setField(service, "issuer", ISSUER);
        ReflectionTestUtils.setField(service, "expirationHours", 2L);
        ReflectionTestUtils.setField(service, "refreshExpirationDays", 30L);
        return service;
    }

    private User persistedUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@valora.dev");
        user.setTokenVersion(7L);
        return user;
    }

    private String redisKey(String token) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        return "auth:refresh:" + HexFormat.of().formatHex(hash);
    }

    private void assertInvalidAccessToken(String token) {
        assertThatThrownBy(() -> tokenService.validateAccessToken(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage(GlobalExceptionHandler.GENERIC_UNAUTHORIZED_MESSAGE)
                .hasMessageNotContaining(token);
    }
}
