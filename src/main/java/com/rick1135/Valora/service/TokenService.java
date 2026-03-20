package com.rick1135.Valora.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.InvalidTokenException;
import com.rick1135.Valora.exception.TokenGenerationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class TokenService {
    @Value("${security.jwt.secret}")
    private String secret;
    @Value("${security.jwt.issuer:valora-api}")
    private String issuer;
    @Value("${security.jwt.expiration-hours:2}")
    private long expirationHours;

    private final Clock clock;

    public TokenService(Clock clock) {
        this.clock = clock;
    }

    public String generateToken(User user){
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            return JWT.create().withSubject(user.getEmail())
                    .withIssuer(issuer)
                    .withExpiresAt(genExpirationDate())
                    .sign(algorithm);
        } catch (JWTCreationException exception){
            throw new TokenGenerationException("Erro ao gerar token JWT.", exception);
        }
    }

    public String validateToken(String token){
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            String subject = JWT.require(algorithm).withIssuer(issuer)
                    .build().verify(token)
                    .getSubject();
            if (subject == null || subject.isBlank()) {
                throw new InvalidTokenException("Token invalido.");
            }
            return subject;
        }  catch (JWTVerificationException exception){
            throw new InvalidTokenException("Token invalido ou expirado.");
        }
    }

    private Instant genExpirationDate(){
        return Instant.now(clock).plus(expirationHours, ChronoUnit.HOURS);
    }
}
