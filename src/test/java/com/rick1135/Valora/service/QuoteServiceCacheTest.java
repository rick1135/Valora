package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteServiceCacheTest {

    @Mock
    private BrapiClient brapiClient;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private QuoteService quoteService;

    private final Map<String, Object> cache = new HashMap<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "test-token");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(any())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(0);
            return keys.stream().map(cache::get).toList();
        });
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            cache.put(key, value);
            return null;
        }).when(valueOperations).set(any(String.class), any(), eq(Duration.ofMinutes(10)));
    }

    @Test
    void shouldUseRedisCacheForSameTicker() {
        when(brapiClient.getQuote("ITSA4", "test-token"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO(
                        "ITSA4",
                        new BigDecimal("11.25"),
                        new BigDecimal("0.55"),
                        998877L,
                        new BigDecimal("11.40"),
                        new BigDecimal("11.10")
                ))));

        QuoteDTO first = quoteService.getCurrentQuote("ITSA4").orElseThrow();
        QuoteDTO second = quoteService.getCurrentQuote("ITSA4").orElseThrow();

        assertThat(first.price()).isEqualByComparingTo("11.25");
        assertThat(second.price()).isEqualByComparingTo("11.25");
        assertThat(cache.get("quotes::ITSA4")).isInstanceOf(QuoteDTO.class);
        assertThat(((QuoteDTO) cache.get("quotes::ITSA4")).changePercent()).isEqualByComparingTo("0.55");
        verify(brapiClient, times(1)).getQuote("ITSA4", "test-token");
    }
}
