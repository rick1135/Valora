package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuoteService {
    private static final String QUOTES_KEY_PREFIX = "quotes::";
    private static final Duration QUOTE_TTL = Duration.ofMinutes(10);

    private final BrapiClient brapiClient;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${brapi.token:}")
    private String brapiToken;

    public Optional<BigDecimal> getCurrentPrice(String ticker) {
        return Optional.ofNullable(getCurrentPrices(List.of(ticker)).get(ticker));
    }

    public Map<String, BigDecimal> getCurrentPrices(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Map.of();
        }

        List<String> uniqueTickers = tickers.stream()
                .filter(ticker -> ticker != null && !ticker.isBlank())
                .distinct()
                .toList();

        if (uniqueTickers.isEmpty()) {
            return Map.of();
        }

        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        List<String> cacheKeys = uniqueTickers.stream()
                .map(this::buildCacheKey)
                .toList();

        try {
            List<Object> cachedValues = redisTemplate.opsForValue().multiGet(cacheKeys);
            if (cachedValues != null) {
                for (int index = 0; index < cacheKeys.size(); index++) {
                    Object cachedValue = cachedValues.get(index);
                    BigDecimal cachedPrice = parseCachedPrice(cachedValue);
                    if (cachedPrice != null) {
                        prices.put(uniqueTickers.get(index), cachedPrice);
                    }
                }
            }
        } catch (Exception exception) {
            log.error("Erro ao consultar cache de cotacoes: {}", exception.getMessage());
        }

        List<String> missingTickers = uniqueTickers.stream()
                .filter(ticker -> !prices.containsKey(ticker))
                .toList();

        if (!missingTickers.isEmpty()) {
            prices.putAll(fetchAndCacheMissingPrices(missingTickers));
        }

        return prices;
    }

    private Map<String, BigDecimal> fetchAndCacheMissingPrices(List<String> missingTickers) {
        if (brapiToken == null || brapiToken.isBlank()) {
            log.warn("Token da Brapi nao configurado. Cotacoes indisponiveis para tickers={}", missingTickers);
            return Map.of();
        }

        try {
            String joinedTickers = String.join(",", missingTickers);
            BrapiResponseDTO response = brapiClient.getQuote(joinedTickers, brapiToken);
            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("Cotacao invalida ou ausente retornada pela Brapi para tickers={}", missingTickers);
                return Map.of();
            }

            Map<String, BigDecimal> fetchedPrices = response.results().stream()
                    .filter(this::hasValidPrice)
                    .collect(Collectors.toMap(
                            BrapiResultDTO::symbol,
                            BrapiResultDTO::regularMarketPrice,
                            (left, right) -> right,
                            LinkedHashMap::new
                    ));

            cachePrices(fetchedPrices);

            List<String> unresolvedTickers = missingTickers.stream()
                    .filter(ticker -> !fetchedPrices.containsKey(ticker))
                    .toList();
            if (!unresolvedTickers.isEmpty()) {
                log.warn("Cotacao invalida ou ausente retornada pela Brapi para tickers={}", unresolvedTickers);
            }

            return fetchedPrices;
        } catch (Exception exception) {
            log.error("Erro ao buscar cotacoes dos ativos tickers={}: {}", missingTickers, exception.getMessage());
            return Map.of();
        }
    }

    private void cachePrices(Map<String, BigDecimal> prices) {
        for (Map.Entry<String, BigDecimal> entry : prices.entrySet()) {
            try {
                redisTemplate.opsForValue().set(buildCacheKey(entry.getKey()), entry.getValue(), QUOTE_TTL);
            } catch (Exception exception) {
                log.error("Erro ao salvar cotacao em cache ticker={}: {}", entry.getKey(), exception.getMessage());
            }
        }
    }

    private boolean hasValidPrice(BrapiResultDTO result) {
        return result != null
                && result.symbol() != null
                && result.regularMarketPrice() != null
                && result.regularMarketPrice().compareTo(BigDecimal.ZERO) > 0;
    }

    private String buildCacheKey(String ticker) {
        return QUOTES_KEY_PREFIX + ticker;
    }

    private BigDecimal parseCachedPrice(Object cachedValue) {
        if (cachedValue instanceof BigDecimal bigDecimal) {
            return bigDecimal.compareTo(BigDecimal.ZERO) > 0 ? bigDecimal : null;
        }
        if (cachedValue instanceof Number number) {
            BigDecimal price = BigDecimal.valueOf(number.doubleValue());
            return price.compareTo(BigDecimal.ZERO) > 0 ? price : null;
        }
        if (cachedValue instanceof Optional<?> optionalValue) {
            return optionalValue
                    .filter(BigDecimal.class::isInstance)
                    .map(BigDecimal.class::cast)
                    .filter(price -> price.compareTo(BigDecimal.ZERO) > 0)
                    .orElse(null);
        }
        if (cachedValue instanceof String stringValue && !stringValue.isBlank()) {
            try {
                BigDecimal price = new BigDecimal(stringValue);
                return price.compareTo(BigDecimal.ZERO) > 0 ? price : null;
            } catch (NumberFormatException exception) {
                log.warn("Valor de cotacao invalido encontrado no cache: {}", stringValue);
            }
        }
        return null;
    }
}
