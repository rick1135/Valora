package com.rick1135.Valora.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class BcbClient {
    private static final String BASE_URL = "https://api.bcb.gov.br/dados/serie";
    private final RestClient restClient;

    public BcbClient(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(BASE_URL).build();
    }

    public List<BcbDataDTO> getSeriesData(int seriesCode, String startDate, String endDate) {
        return restClient.get()
                .uri("/bcdata.sgs.{seriesCode}/dados?formato=json&dataInicial={startDate}&dataFinal={endDate}",
                        seriesCode, startDate, endDate)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<BcbDataDTO>>() {});
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class BcbDataDTO {
        private String data;
        private String valor;
    }
}
