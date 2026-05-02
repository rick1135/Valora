package com.rick1135.Valora.config;

import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;

@TestConfiguration
public class TestRedisConfig {

    @Bean
    RedisTemplate<String, Object> redisTemplate() {
        return Mockito.mock(RedisTemplate.class, Answers.RETURNS_DEEP_STUBS);
    }
}
