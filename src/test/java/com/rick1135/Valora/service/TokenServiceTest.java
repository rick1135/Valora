package com.rick1135.Valora.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.InvalidTokenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private TokenService tokenService;

    private static final String SECRET = "test-secret-key";
    private static final String ISSUER = "valora-api";

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(
                Clock.systemUTC(),
                redisTemplate
        );
        ReflectionTestUtils.setField(tokenService, "secret", SECRET);
        ReflectionTestUtils.setField(tokenService, "issuer", ISSUER);
        ReflectionTestUtils.setField(tokenService, "expirationHours", 2L);
        ReflectionTestUtils.setField(tokenService, "refreshExpirationDays", 30L);
    }

    @Test
    void generateRefreshTokenStoresHashedKeyWithTtl() {
        User user = new User();
        user.setEmail("user@valora.dev");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String refreshToken = tokenService.generateRefreshToken(user);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), org.mockito.Mockito.eq("user@valora.dev"), org.mockito.Mockito.eq(Duration.ofDays(30)));
        String redisKey = keyCaptor.getValue();

        assertThat(refreshToken).isNotBlank();
        assertThat(redisKey).startsWith("auth:refresh:");
        assertThat(redisKey).doesNotContain(refreshToken);
    }

    @Test
    void consumeRefreshTokenUsesAtomicGetAndDelete() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(anyString())).thenReturn("user@valora.dev");

        String email = tokenService.consumeRefreshToken("refresh-token");

        assertThat(email).isEqualTo("user@valora.dev");
        verify(valueOperations).getAndDelete(anyString());
    }

    @Test
    void consumeRefreshTokenRejectsMissingEntry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        assertThatThrownBy(() -> tokenService.consumeRefreshToken("refresh-token"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Refresh token invalido ou expirado.");
    }

    @Test
    void validateTokenShouldReturnSubjectForValidAccessToken() {
        User user = user("user@valora.dev");

        String token = tokenService.generateToken(user);

        assertThat(tokenService.validateToken(token)).isEqualTo("user@valora.dev");
    }

    @Test
    void validateTokenShouldRejectExpiredAccessTokenWithGenericMessage() {
        String token = createToken("user@valora.dev", ISSUER, SECRET, Instant.now().minus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> tokenService.validateToken(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Token invalido ou expirado.")
                .hasMessageNotContaining(token)
                .hasMessageNotContaining("JWTVerificationException");
    }

    @Test
    void validateTokenShouldRejectWrongIssuerAccessTokenWithGenericMessage() {
        String token = createToken("user@valora.dev", "other-issuer", SECRET, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> tokenService.validateToken(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Token invalido ou expirado.")
                .hasMessageNotContaining(token)
                .hasMessageNotContaining("InvalidClaimException");
    }

    @Test
    void validateTokenShouldRejectTamperedAccessTokenWithGenericMessage() {
        String token = createToken("user@valora.dev", ISSUER, "other-secret", Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> tokenService.validateToken(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Token invalido ou expirado.")
                .hasMessageNotContaining(token)
                .hasMessageNotContaining("SignatureVerificationException");
    }

    @Test
    void validateTokenShouldRejectMalformedAccessTokenWithGenericMessage() {
        String token = "not-a-jwt";

        assertThatThrownBy(() -> tokenService.validateToken(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Token invalido ou expirado.")
                .hasMessageNotContaining(token)
                .hasMessageNotContaining("JWTDecodeException");
    }

    @Test
    void validateTokenShouldRejectBlankSubjectAccessTokenWithGenericMessage() {
        String token = createToken(" ", ISSUER, SECRET, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> tokenService.validateToken(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Token invalido.")
                .hasMessageNotContaining(token)
                .hasMessageNotContaining("JWTVerificationException");
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    private String createToken(String subject, String issuer, String secret, Instant expiresAt) {
        return JWT.create()
                .withSubject(subject)
                .withIssuer(issuer)
                .withExpiresAt(Date.from(expiresAt))
                .sign(Algorithm.HMAC256(secret));
    }
}
