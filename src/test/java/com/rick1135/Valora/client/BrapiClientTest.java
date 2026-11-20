package com.rick1135.Valora.client;

import com.rick1135.Valora.dto.brapi.BrapiListedAssetDTO;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BrapiClientTest {

    @Test
    void listQuotesSendsPaginationAndReadsCatalogEntries() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrapiClient client = new BrapiClient(builder);

        server.expect(requestTo("https://brapi.dev/api/quote/list?limit=100&page=2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-ok"))
                .andRespond(withSuccess("""
                        {"stocks":[{"stock":"ITSA4","name":"Itausa","type":"stock","close":10},
                                   {"stock":"SPXB11","name":"S&P 500","type":"fund"}],
                         "currentPage":2,"totalPages":3,"hasNextPage":true,"indexes":[]}
                        """, MediaType.APPLICATION_JSON));

        var response = client.listQuotes(null, 100, 2, "token-ok");

        assertThat(response.stocks()).containsExactly(
                new BrapiListedAssetDTO("ITSA4", "Itausa", "stock"),
                new BrapiListedAssetDTO("SPXB11", "S&P 500", "fund"));
        assertThat(response.currentPage()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.hasNextPage()).isTrue();
        server.verify();
    }

    @Test
    void listQuotesEncodesSearchAndOmitsAbsentPagination() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BrapiClient client = new BrapiClient(builder);

        server.expect(requestTo("https://brapi.dev/api/quote/list?search=S%26P%20%2B%20500"))
                .andRespond(withSuccess("""
                        {"stocks":[],"hasNextPage":false}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.listQuotes("S&P + 500", null, null, "token-ok").stocks()).isEmpty();
        server.verify();
    }

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
                        """, MediaType.APPLICATION_JSON));

        BrapiResponseDTO response = client.getQuoteWithDividends("PETR4", "token-ok");

        assertThat(response.results()).isEmpty();
        server.verify();
    }
}
