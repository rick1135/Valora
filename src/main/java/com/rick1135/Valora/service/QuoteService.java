package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
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
import java.util.Objects;
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
        return getCurrentQuote(ticker).map(QuoteDTO::price);
    }

    public Optional<QuoteDTO> getCurrentQuote(String ticker) {
        return Optional.ofNullable(getCurrentQuotes(List.of(ticker)).get(ticker));
    }

    public Map<String, BigDecimal> getCurrentPrices(List<String> tickers) {
        return getCurrentQuotes(tickers).entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().price() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().price(),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
    }

    public Map<String, QuoteDTO> getCurrentQuotes(List<String> tickers) {
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

        Map<String, QuoteDTO> quotes = new LinkedHashMap<>();
        List<String> cacheKeys = uniqueTickers.stream()
                .map(this::buildCacheKey)
                .toList();

        try {
            List<Object> cachedValues = redisTemplate.opsForValue().multiGet(cacheKeys);
            if (cachedValues != null) {
                for (int index = 0; index < cacheKeys.size(); index++) {
                    QuoteDTO cachedQuote = parseCachedQuote(uniqueTickers.get(index), cachedValues.get(index));
                    if (cachedQuote != null) {
                        quotes.put(uniqueTickers.get(index), cachedQuote);
                    }
                }
            }
        } catch (Exception exception) {
            log.error("Erro ao consultar cache de cotacoes: {}", exception.getMessage());
        }

        List<String> missingTickers = uniqueTickers.stream()
                .filter(ticker -> !quotes.containsKey(ticker))
                .toList();

        if (!missingTickers.isEmpty()) {
            quotes.putAll(fetchAndCacheMissingQuotes(missingTickers));
        }

        return quotes;
    }

    private Map<String, QuoteDTO> fetchAndCacheMissingQuotes(List<String> missingTickers) {
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

            Map<String, QuoteDTO> fetchedQuotes = response.results().stream()
                    .filter(this::hasValidPrice)
                    .collect(Collectors.toMap(
                            BrapiResultDTO::symbol,
                            this::toQuote,
                            (left, right) -> right,
                            LinkedHashMap::new
                    ));

            cacheQuotes(fetchedQuotes);

            List<String> unresolvedTickers = missingTickers.stream()
                    .filter(ticker -> !fetchedQuotes.containsKey(ticker))
                    .toList();
            if (!unresolvedTickers.isEmpty()) {
                log.warn("Cotacao invalida ou ausente retornada pela Brapi para tickers={}", unresolvedTickers);
            }

            return fetchedQuotes;
        } catch (Exception exception) {
            log.error("Erro ao buscar cotacoes dos ativos tickers={}: {}", missingTickers, exception.getMessage());
            return Map.of();
        }
    }

    private void cacheQuotes(Map<String, QuoteDTO> quotes) {
        for (Map.Entry<String, QuoteDTO> entry : quotes.entrySet()) {
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

    private QuoteDTO toQuote(BrapiResultDTO result) {
        return new QuoteDTO(
                result.symbol(),
                result.regularMarketPrice(),
                result.regularMarketChangePercent(),
                result.regularMarketVolume(),
                result.regularMarketDayHigh(),
                result.regularMarketDayLow()
        );
    }

    private String buildCacheKey(String ticker) {
        return QUOTES_KEY_PREFIX + ticker;
    }

    private QuoteDTO parseCachedQuote(String ticker, Object cachedValue) {
        if (cachedValue instanceof QuoteDTO quote) {
            return quote.price() != null && quote.price().compareTo(BigDecimal.ZERO) > 0 ? quote : null;
        }
        if (cachedValue instanceof BigDecimal bigDecimal) {
            return bigDecimal.compareTo(BigDecimal.ZERO) > 0 ? new QuoteDTO(ticker, bigDecimal, null, null, null, null) : null;
        }
        if (cachedValue instanceof Number number) {
            BigDecimal price = BigDecimal.valueOf(number.doubleValue());
            return price.compareTo(BigDecimal.ZERO) > 0 ? new QuoteDTO(ticker, price, null, null, null, null) : null;
        }
        if (cachedValue instanceof Optional<?> optionalValue) {
            return optionalValue.stream()
                    .map(value -> parseCachedQuote(ticker, value))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        if (cachedValue instanceof String stringValue && !stringValue.isBlank()) {
            try {
                BigDecimal price = new BigDecimal(stringValue);
                return price.compareTo(BigDecimal.ZERO) > 0 ? new QuoteDTO(ticker, price, null, null, null, null) : null;
            } catch (NumberFormatException exception) {
                log.warn("Valor de cotacao invalido encontrado no cache: {}", stringValue);
            }
        }
        return null;
    }
}
