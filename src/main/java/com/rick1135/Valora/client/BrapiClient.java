package com.rick1135.Valora.client;

import com.rick1135.Valora.dto.brapi.BrapiQuoteListResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class BrapiClient {
    private static final String BASE_URL = "https://brapi.dev/api";

    private final RestClient restClient;

    public BrapiClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(BASE_URL).build();
    }

    public BrapiResponseDTO getQuote(String ticker, String token) {
        return request(ticker, token, false, null);
    }

    public BrapiResponseDTO getQuoteWithDividends(String ticker, String token) {
        return request(ticker, token, true, null);
    }

    public BrapiResponseDTO getHistoricalQuote(String ticker, String range, String token) {
        return request(ticker, token, false, range);
    }

    public BrapiQuoteListResponseDTO listQuotes(@Nullable String search, @Nullable Integer limit,
                                               @Nullable Integer page, String token) {
        return listQuotes(search, limit, page, null, null, token);
    }

    public BrapiQuoteListResponseDTO listQuotes(@Nullable String search, @Nullable Integer limit,
                                               @Nullable Integer page, @Nullable String sortBy,
                                               @Nullable String sortOrder, String token) {
        return restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/quote/list")
                            .queryParamIfPresent("limit", Optional.ofNullable(limit))
                            .queryParamIfPresent("page", Optional.ofNullable(page))
                            .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy))
                            .queryParamIfPresent("sortOrder", Optional.ofNullable(sortOrder));
                    if (search != null) {
                        return builder.queryParam("search", "{search}").build(search);
                    }
                    return builder.build();
                })
                .headers(headers -> headers.setBearerAuth(token))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(BrapiQuoteListResponseDTO.class);
    }

    private BrapiResponseDTO request(String tickers, String token, boolean includeDividends, String range) {
        return restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/quote/{tickers}");
                    if (includeDividends) {
                        builder.queryParam("dividends", true);
                    }
                    if (range != null) {
                        builder.queryParam("range", range);
                        builder.queryParam("interval", "1d");
                    }
                    return builder.build(tickers);
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(BrapiResponseDTO.class);
    }
}
