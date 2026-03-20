package com.rick1135.Valora.client;

import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BrapiClient {
    private static final String BASE_URL = "https://brapi.dev/api";
    private final RestClient restClient = RestClient.builder()
            .baseUrl(BASE_URL)
            .build();

    public BrapiResponseDTO getQuote(String ticker, String token) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/quote/{ticker}")
                        .queryParam("token", token)
                        .build(ticker))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(BrapiResponseDTO.class);
    }
}
