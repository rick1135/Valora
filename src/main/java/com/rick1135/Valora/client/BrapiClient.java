package com.rick1135.Valora.client;

import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BrapiClient {
    private static final String BASE_URL = "https://brapi.dev/api";

    private final RestClient restClient;

    public BrapiClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(BASE_URL).build();
    }

    public BrapiResponseDTO getQuote(String ticker, String token) {
        return request(ticker, token, false);
    }

    public BrapiResponseDTO getQuoteWithDividends(String ticker, String token) {
        return request(ticker, token, true);
    }

    private BrapiResponseDTO request(String tickers, String token, boolean includeDividends) {
        return restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/quote/{tickers}");
                    if (includeDividends) {
                        builder.queryParam("dividends", true);
                    }
                    return builder.build(tickers);
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(BrapiResponseDTO.class);
    }
}
