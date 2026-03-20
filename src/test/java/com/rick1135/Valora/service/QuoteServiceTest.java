package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteServiceTest {

    @Mock
    private BrapiClient brapiClient;

    @InjectMocks
    private QuoteService quoteService;

    @Test
    void shouldReturnEmptyWhenTokenIsMissing() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "");

        Optional<BigDecimal> result = quoteService.getCurrentPrice("PETR4");

        assertThat(result).isEmpty();
        verify(brapiClient, never()).getQuote("PETR4", "");
    }

    @Test
    void shouldReturnPriceWhenBrapiReturnsValidQuote() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "token-ok");
        when(brapiClient.getQuote("PETR4", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO("PETR4", new BigDecimal("36.15")))));

        Optional<BigDecimal> result = quoteService.getCurrentPrice("PETR4");

        assertThat(result).contains(new BigDecimal("36.15"));
    }

    @Test
    void shouldReturnEmptyWhenQuoteIsInvalid() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "token-ok");
        when(brapiClient.getQuote("PETR4", "token-ok"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO("PETR4", BigDecimal.ZERO))));

        Optional<BigDecimal> result = quoteService.getCurrentPrice("PETR4");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenBrapiClientThrowsException() {
        ReflectionTestUtils.setField(quoteService, "brapiToken", "token-ok");
        when(brapiClient.getQuote("PETR4", "token-ok"))
                .thenThrow(new RuntimeException("boom"));

        Optional<BigDecimal> result = quoteService.getCurrentPrice("PETR4");

        assertThat(result).isEmpty();
    }
}
