package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {QuoteService.class, QuoteServiceCacheTest.CacheTestConfig.class})
@TestPropertySource(properties = "brapi.token=test-token")
class QuoteServiceCacheTest {

    @Autowired
    private QuoteService quoteService;

    @Autowired
    private BrapiClient brapiClient;

    @Test
    void shouldUseCacheForSameTicker() {
        when(brapiClient.getQuote("ITSA4", "test-token"))
                .thenReturn(new BrapiResponseDTO(List.of(new BrapiResultDTO("ITSA4", new BigDecimal("11.25")))));

        BigDecimal first = quoteService.getCurrentPrice("ITSA4").orElseThrow();
        BigDecimal second = quoteService.getCurrentPrice("ITSA4").orElseThrow();

        assertThat(first).isEqualByComparingTo("11.25");
        assertThat(second).isEqualByComparingTo("11.25");
        verify(brapiClient, times(1)).getQuote("ITSA4", "test-token");
    }

    @Configuration
    @EnableCaching
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("quotes");
        }

        @Bean
        BrapiClient brapiClient() {
            return mock(BrapiClient.class);
        }
    }
}
