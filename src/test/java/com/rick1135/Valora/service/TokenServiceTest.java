package com.rick1135.Valora.service;

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
import java.time.ZoneOffset;

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

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(
                Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC),
                redisTemplate
        );
    }

    @Test
    void generateRefreshTokenStoresHashedKeyWithTtl() {
        User user = new User();
        user.setEmail("user@valora.dev");
        ReflectionTestUtils.setField(tokenService, "refreshExpirationDays", 30L);
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
}
