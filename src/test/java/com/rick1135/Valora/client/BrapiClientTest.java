package com.rick1135.Valora.client;

import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BrapiClientTest {

    @Test
    void getQuoteWithDividendsShouldSendBearerTokenInAuthorizationHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrapiClient client = new BrapiClient(builder);

        server.expect(requestTo("https://brapi.dev/api/quote/PETR4?dividends=true"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-ok"))
                .andRespond(withSuccess("""
                        {"results":[]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        BrapiResponseDTO response = client.getQuoteWithDividends("PETR4", "token-ok");

        assertThat(response.results()).isEmpty();
        server.verify();
    }
}
