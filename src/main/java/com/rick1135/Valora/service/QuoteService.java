package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuoteService {
    private final BrapiClient brapiClient;

    @Value("${brapi.token:}")
    private String brapiToken;

    public Optional<BigDecimal> getCurrentPrice(String ticker) {
        if (brapiToken == null || brapiToken.isBlank()) {
            log.warn("Token da Brapi nao configurado. Cotacao indisponivel para ticker={}", ticker);
            return Optional.empty();
        }

        try {
            BrapiResponseDTO response = brapiClient.getQuote(ticker, brapiToken);
            if (response != null && response.results() != null && !response.results().isEmpty()) {
                BigDecimal price = response.results().getFirst().regularMarketPrice();
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    return Optional.of(price);
                }
            }
            log.warn("Cotacao invalida ou ausente retornada pela Brapi para ticker={}", ticker);
        } catch (Exception exception) {
            log.error("Erro ao buscar cotacao do ativo ticker={}: {}", ticker, exception.getMessage());
        }

        return Optional.empty();
    }
}
