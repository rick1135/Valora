package com.rick1135.Valora.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.exception.InvalidTokenException;
import com.rick1135.Valora.exception.TokenGenerationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class TokenService {
    private static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";
    private static final String USER_REFRESH_TOKEN_PREFIX = "auth:refresh:user:";
    // Lua needs raw strings for key names, payloads and TTLs. The shared template uses JSON for values.
    private static final RedisSerializer<String> LUA_SERIALIZER = RedisSerializer.string();
    private static final RedisSerializer<Long> LUA_INTEGER_SERIALIZER = new GenericToStringSerializer<>(Long.class);
    private static final DefaultRedisScript<Long> STORE_REFRESH_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('SADD', KEYS[2], KEYS[1])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<String> CONSUME_REFRESH_SCRIPT = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return nil end
            redis.call('DEL', KEYS[1])
            local uid = string.match(v, '^([^:]+)')
            if uid then redis.call('SREM', ARGV[1] .. uid, KEYS[1]) end
            return v
            """, String.class);
    private static final DefaultRedisScript<String> REVOKE_SINGLE_SCRIPT = CONSUME_REFRESH_SCRIPT;
    private static final DefaultRedisScript<Long> REVOKE_ALL_USER_TOKENS_SCRIPT = new DefaultRedisScript<>("""
            local keys = redis.call('SMEMBERS', KEYS[1])
            for i = 1, #keys do redis.call('DEL', keys[i]) end
            redis.call('DEL', KEYS[1])
            return #keys
            """, Long.class);

    public record VerifiedAccessToken(UUID userId, long tokenVersion) {}

    public record ConsumedRefreshToken(UUID userId, long tokenVersion) {}

    @Value("${security.jwt.secret}")
    private String secret;
    @Value("${security.jwt.issuer:valora-api}")
    private String issuer;
    @Value("${security.jwt.expiration-hours:2}")
    private long expirationHours;
    @Value("${security.jwt.refresh-expiration-days:30}")
    private long refreshExpirationDays;

    private final Clock clock;
    private final RedisTemplate<String, Object> redisTemplate;

    public TokenService(Clock clock, RedisTemplate<String, Object> redisTemplate) {
        this.clock = clock;
        this.redisTemplate = redisTemplate;
    }

    public String generateToken(User user) {
        try {
            if (user.getId() == null) {
                throw new IllegalArgumentException("User id is required to generate an access token.");
            }
            Algorithm algorithm = Algorithm.HMAC256(secret);
            return JWT.create().withSubject(user.getId().toString())
                    .withClaim("tv", user.getTokenVersion())
                    .withIssuer(issuer)
                    .withExpiresAt(genExpirationDate())
                    .sign(algorithm);
        } catch (JWTCreationException | IllegalArgumentException exception) {
            throw new TokenGenerationException("Erro ao gerar token JWT.", exception);
        }
    }

    public String validateToken(String token) {
        return validateAccessToken(token).userId().toString();
    }

    public VerifiedAccessToken validateAccessToken(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            var decoded = JWT.require(algorithm).withIssuer(issuer).build().verify(token);
            String subject = decoded.getSubject();
            Object tokenVersion = decoded.getClaim("tv").as(Object.class);
            if (subject == null || !(tokenVersion instanceof Integer || tokenVersion instanceof Long)) {
                throw new InvalidTokenException(GlobalExceptionHandler.GENERIC_UNAUTHORIZED_MESSAGE);
            }
            UUID userId = UUID.fromString(subject);
            if (!userId.toString().equalsIgnoreCase(subject)) {
                throw new InvalidTokenException(GlobalExceptionHandler.GENERIC_UNAUTHORIZED_MESSAGE);
            }
            return new VerifiedAccessToken(userId, ((Number) tokenVersion).longValue());
        } catch (JWTVerificationException | IllegalArgumentException exception) {
            throw new InvalidTokenException(GlobalExceptionHandler.GENERIC_UNAUTHORIZED_MESSAGE);
        }
    }

    public String generateRefreshToken(User user) {
        String refreshToken = UUID.randomUUID() + "." + UUID.randomUUID();
        try {
            if (user.getId() == null) {
                throw new IllegalArgumentException("User id is required to generate refresh token.");
            }
            String refreshKey = buildRefreshKey(refreshToken);
            String indexKey = USER_REFRESH_TOKEN_PREFIX + user.getId();
            String payload = user.getId() + ":" + user.getTokenVersion();
            long ttlSeconds = Duration.ofDays(refreshExpirationDays).toSeconds();
            Long stored = redisTemplate.execute(STORE_REFRESH_SCRIPT, LUA_SERIALIZER, LUA_INTEGER_SERIALIZER,
                    List.of(refreshKey, indexKey), payload, String.valueOf(ttlSeconds));
            if (stored == null || stored != 1L) {
                throw new IllegalStateException("Redis did not confirm refresh token storage.");
            }
            return refreshToken;
        } catch (Exception exception) {
            throw new TokenGenerationException("Erro ao gerar refresh token.", exception);
        }
    }

    public UUID consumeRefreshToken(String refreshToken) {
        return consumeRefreshTokenWithVersion(refreshToken).userId();
    }

    public ConsumedRefreshToken consumeRefreshTokenWithVersion(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidTokenException(GlobalExceptionHandler.GENERIC_REFRESH_TOKEN_MESSAGE);
        }

        try {
            String storedValue = redisTemplate.execute(CONSUME_REFRESH_SCRIPT, LUA_SERIALIZER, LUA_SERIALIZER,
                    List.of(buildRefreshKey(refreshToken)), USER_REFRESH_TOKEN_PREFIX);
            if (storedValue != null) {
                int separator = storedValue.indexOf(':');
                if (separator >= 0) {
                    UUID userId = UUID.fromString(storedValue.substring(0, separator));
                    long tokenVersion = Long.parseLong(storedValue.substring(separator + 1));
                    return new ConsumedRefreshToken(userId, tokenVersion);
                }
            }
        } catch (RuntimeException exception) {
            throw new InvalidTokenException(GlobalExceptionHandler.GENERIC_REFRESH_TOKEN_MESSAGE);
        }
        throw new InvalidTokenException(GlobalExceptionHandler.GENERIC_REFRESH_TOKEN_MESSAGE);
    }

    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        try {
            redisTemplate.execute(REVOKE_SINGLE_SCRIPT, LUA_SERIALIZER, LUA_SERIALIZER,
                    List.of(buildRefreshKey(refreshToken)), USER_REFRESH_TOKEN_PREFIX);
        } catch (RuntimeException exception) {
            throw new TokenGenerationException("Erro ao revogar refresh token.", exception);
        }
    }

    public void revokeAllUserTokens(UUID userId) {
        if (userId == null) {
            return;
        }

        try {
            Long revoked = redisTemplate.execute(REVOKE_ALL_USER_TOKENS_SCRIPT,
                    List.of(USER_REFRESH_TOKEN_PREFIX + userId));
            if (revoked == null) {
                throw new IllegalStateException("Redis did not confirm refresh token revocation.");
            }
        } catch (RuntimeException exception) {
            throw new TokenGenerationException("Erro ao revogar refresh tokens do usuario.", exception);
        }
    }

    private Instant genExpirationDate() {
        return Instant.now(clock).plus(expirationHours, ChronoUnit.HOURS);
    }

    private String buildRefreshKey(String refreshToken) {
        return REFRESH_TOKEN_PREFIX + sha256(refreshToken);
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte currentByte : hash) {
                hex.append(String.format("%02x", currentByte));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel.", exception);
        }
    }
}
