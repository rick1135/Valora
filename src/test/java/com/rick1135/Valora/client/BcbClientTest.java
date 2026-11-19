package com.rick1135.Valora.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BcbClientTest {

    @Test
    void getSeriesDataShouldBuildValidCdiUrlAndDeserializeResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BcbClient client = new BcbClient(builder);

        server.expect(requestTo("https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados?formato=json&dataInicial=01%2F01%2F2026&dataFinal=10%2F01%2F2026"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"data":"02/01/2026","valor":"0.055131"},
                          {"data":"05/01/2026","valor":"0.055131"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<BcbClient.BcbDataDTO> data = client.getSeriesData(12, "01/01/2026", "10/01/2026");

        assertThat(data).hasSize(2);
        assertThat(data.get(0).getData()).isEqualTo("02/01/2026");
        assertThat(data.get(0).getValor()).isEqualTo("0.055131");
        server.verify();
    }

    @Test
    void getSeriesDataShouldBuildValidIpcaUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BcbClient client = new BcbClient(builder);

        server.expect(requestTo("https://api.bcb.gov.br/dados/serie/bcdata.sgs.433/dados?formato=json&dataInicial=01%2F01%2F2026&dataFinal=31%2F01%2F2026"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"data":"01/01/2026","valor":"0.52"}]
                        """, MediaType.APPLICATION_JSON));

        List<BcbClient.BcbDataDTO> data = client.getSeriesData(433, "01/01/2026", "31/01/2026");

        assertThat(data).hasSize(1);
        assertThat(data.get(0).getValor()).isEqualTo("0.52");
        server.verify();
    }
}
