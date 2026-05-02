package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock
    private BrapiClient brapiClient;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private QuoteService quoteService;

    @Test
    void shouldReturnEmptyWhenTokenIsMissing() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(any())).thenReturn(Collections.singletonList(null));

        Optional<BigDecimal> result = quoteService.getCurrentPrice("PETR4");

        assertThat(result).isEmpty();
        verify(brapiClient, never()).getQuote("PETR4", "");
    }

    @Test
    void shouldReturnPriceWhenBrapiReturnsValidQuote() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "token-ok");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(any())).thenReturn(Collections.singletonList(null));
        when(brapiClient.getQuote("PETR4", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO("PETR4", new BigDecimal("36.15")))));

        Optional<BigDecimal> result = quoteService.getCurrentPrice("PETR4");

        assertThat(result).contains(new BigDecimal("36.15"));
    }

    @Test
    void shouldReturnCompleteQuoteWhenBrapiReturnsMarketData() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "token-ok");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(any())).thenReturn(Collections.singletonList(null));
        when(brapiClient.getQuote("PETR4", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO(
                        "PETR4",
                        new BigDecimal("36.15"),
                        new BigDecimal("1.25"),
                        1234567L,
                        new BigDecimal("36.50"),
                        new BigDecimal("35.80")
                ))));

        QuoteDTO result = quoteService.getCurrentQuote("PETR4").orElseThrow();

        assertThat(result.price()).isEqualByComparingTo("36.15");
        assertThat(result.changePercent()).isEqualByComparingTo("1.25");
        assertThat(result.volume()).isEqualTo(1234567L);
        assertThat(result.high()).isEqualByComparingTo("36.50");
        assertThat(result.low()).isEqualByComparingTo("35.80");
    }

    @Test
    void shouldReturnEmptyWhenQuoteIsInvalid() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "token-ok");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(any())).thenReturn(Collections.singletonList(null));
        when(brapiClient.getQuote("PETR4", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO("PETR4", BigDecimal.ZERO))));

        Optional<BigDecimal> result = quoteService.getCurrentPrice("PETR4");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenBrapiClientThrowsException() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "token-ok");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(any())).thenReturn(Collections.singletonList(null));
        when(brapiClient.getQuote("PETR4", "token-ok"))
                .thenThrow(new RuntimeException("boom"));

        Optional<BigDecimal> result = quoteService.getCurrentPrice("PETR4");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldFetchOnlyMissingTickersInBatch() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "token-ok");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of("quotes::PETR4", "quotes::VALE3")))
                .thenReturn(java.util.Arrays.asList(new QuoteDTO("PETR4", new BigDecimal("31.10"), null, null, null, null), null));
        when(brapiClient.getQuote("VALE3", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO("VALE3", new BigDecimal("66.40")))));

        Map<String, BigDecimal> prices = quoteService.getCurrentPrices(List.of("PETR4", "VALE3"));

        assertThat(prices).containsEntry("PETR4", new BigDecimal("31.10"));
        assertThat(prices).containsEntry("VALE3", new BigDecimal("66.40"));
        verify(brapiClient).getQuote("VALE3", "token-ok");
    }
}
