package com.rick1135.Valora.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static com.rick1135.Valora.config.RequestCorrelationFilter.HEADER_NAME;
import static com.rick1135.Valora.config.RequestCorrelationFilter.MDC_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void generatesUuidWhenHeaderIsAbsent() throws Exception {
        filter.doFilter(request, response, (req, res) -> assertGeneratedRequestId());

        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void propagatesValidRequestId() throws Exception {
        String requestId = "request.ABC_123-";
        request.addHeader(HEADER_NAME, requestId);

        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get(MDC_KEY)).isEqualTo(requestId);
            assertThat(response.getHeader(HEADER_NAME)).isEqualTo(requestId);
        });

        assertThat(response.getHeader(HEADER_NAME)).isEqualTo(requestId);
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"request\r\nInjected: value", "request\n", "request id",
            "1234567890123456789012345678901234567890123456789012345678901234567890"})
    void replacesInvalidRequestId(String invalidRequestId) throws Exception {
        request.addHeader(HEADER_NAME, invalidRequestId);

        filter.doFilter(request, response, (req, res) -> assertGeneratedRequestId());

        assertThat(response.getHeader(HEADER_NAME)).isNotEqualTo(invalidRequestId);
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcWhenFilterChainThrows() {
        ServletException failure = new ServletException("Filter chain failed");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertGeneratedRequestId();
            throw failure;
        })).isSameAs(failure);

        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    private void assertGeneratedRequestId() {
        String requestId = response.getHeader(HEADER_NAME);
        assertThat(requestId).isNotNull();
        UUID uuid = UUID.fromString(requestId);
        assertThat(uuid.version()).isEqualTo(4);
        assertThat(requestId).isEqualTo(uuid.toString());
        assertThat(MDC.get(MDC_KEY)).isEqualTo(requestId);
    }
}
