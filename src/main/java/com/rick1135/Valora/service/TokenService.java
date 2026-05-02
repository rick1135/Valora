package com.rick1135.Valora.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.InvalidTokenException;
import com.rick1135.Valora.exception.TokenGenerationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class TokenService {
    private static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";

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
            Algorithm algorithm = Algorithm.HMAC256(secret);
            return JWT.create().withSubject(user.getEmail())
                    .withIssuer(issuer)
                    .withExpiresAt(genExpirationDate())
                    .sign(algorithm);
        } catch (JWTCreationException exception) {
            throw new TokenGenerationException("Erro ao gerar token JWT.", exception);
        }
    }

    public String validateToken(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            String subject = JWT.require(algorithm).withIssuer(issuer)
                    .build().verify(token)
                    .getSubject();
            if (subject == null || subject.isBlank()) {
                throw new InvalidTokenException("Token invalido.");
            }
            return subject;
        } catch (JWTVerificationException exception) {
            throw new InvalidTokenException("Token invalido ou expirado.");
        }
    }

    public String generateRefreshToken(User user) {
        String refreshToken = UUID.randomUUID() + "." + UUID.randomUUID();
        try {
            redisTemplate.opsForValue().set(
                    buildRefreshKey(refreshToken),
                    user.getEmail(),
                    Duration.ofDays(refreshExpirationDays)
            );
            return refreshToken;
        } catch (Exception exception) {
            throw new TokenGenerationException("Erro ao gerar refresh token.", exception);
        }
    }

    public String consumeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidTokenException("Refresh token invalido ou expirado.");
        }

        Object storedValue;
        try {
            storedValue = redisTemplate.opsForValue().getAndDelete(buildRefreshKey(refreshToken));
        } catch (Exception exception) {
            throw new InvalidTokenException("Refresh token invalido ou expirado.");
        }

        if (!(storedValue instanceof String email) || email.isBlank()) {
            throw new InvalidTokenException("Refresh token invalido ou expirado.");
        }

        return email;
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
