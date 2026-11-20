package com.rick1135.Valora.security;

import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {
    private static final String CLIENT_IP = "192.0.2.10";
    private static final String AUTH_KEY = "ratelimit:auth:ip:" + CLIENT_IP;
    private static final String API_IP_KEY = "ratelimit:api:ip:" + CLIENT_IP;
    private static final String LIMIT = "X-RateLimit-Limit";
    private static final String REMAINING = "X-RateLimit-Remaining";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final RateLimitProperties properties = new RateLimitProperties();
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final RateLimitFilter filter = new RateLimitFilter(redisTemplate, properties, objectMapper);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/login", "/auth/register", "/auth/refresh"})
    void publicAuthRoutesShareAnIpBucket(String path) throws Exception {
        var request = request("POST", "/valora" + path);
        request.setContextPath("/valora");
        request.addHeader("X-Forwarded-For", "203.0.113.20");
        authenticate(UUID.randomUUID());
        properties.setAuthRequestsPerMinute(3);
        properties.setWindowSeconds(45);
        stubCounter(AUTH_KEY, 3, 40);

        filter.doFilter(request, response, chain);

        verify(redisTemplate).execute(any(), eq(List.of(AUTH_KEY)), eq("45"));
        verify(chain).doFilter(request, response);
        assertThat(response.getHeader(LIMIT)).isEqualTo("3");
        assertThat(response.getHeader(REMAINING)).isEqualTo("0");
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void apiUsesTheAuthenticatedUserId() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(userId);
        var request = request("GET", "/portfolios");
        stubCounter("ratelimit:api:user:" + userId, 20, 25);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getHeader(LIMIT)).isEqualTo("120");
        assertThat(response.getHeader(REMAINING)).isEqualTo("100");
    }

    @Test
    void apiFallsBackToIpWhenPrincipalHasNoId() throws Exception {
        authenticate(null);
        var request = request("GET", "/portfolios");
        stubCounter(API_IP_KEY, 1, 60);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getHeader(LIMIT)).isEqualTo("120");
        assertThat(response.getHeader(REMAINING)).isEqualTo("119");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 17L})
    void exceededLimitReturns429WithRetryAfterAndErrorBody(long ttl) throws Exception {
        var request = request("POST", "/auth/login");
        stubCounter(AUTH_KEY, 16, ttl);

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertThat(response.getHeader(LIMIT)).isEqualTo("15");
        assertThat(response.getHeader(REMAINING)).isEqualTo("0");
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo(ttl == 0 ? "1" : "17");
        assertError(HttpStatus.TOO_MANY_REQUESTS, request.getRequestURI());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/static/app.html", "/static/app.css", "/static/app.js",
            "/favicon.ico", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    void staticFilesAndHealthProbesBypassRateLimiting(String path) throws Exception {
        assertBypass(filter, request("GET", path));
    }

    @Test
    void optionsBypassesRateLimiting() throws Exception {
        assertBypass(filter, request("OPTIONS", "/auth/login"));
    }

    @Test
    void disabledLimiterBypassesRateLimiting() throws Exception {
        properties.setEnabled(false);
        assertBypass(filter, request("POST", "/auth/login"));
    }

    @Test
    void missingRedisTemplateBypassesRateLimitingWhenFailOpen() throws Exception {
        properties.setFailOpen(true);
        assertBypass(new RateLimitFilter(null, properties, objectMapper), request("POST", "/auth/login"));
    }

    @Test
    void missingRedisTemplateReturns503WhenFailClosed() throws Exception {
        properties.setFailOpen(false);
        var request = request("POST", "/auth/login");

        new RateLimitFilter(null, properties, objectMapper).doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertError(HttpStatus.SERVICE_UNAVAILABLE, request.getRequestURI());
    }

    @Test
    void forwardedHeaderFilterUsesProxyClientIpWhenUntrustedForwardedIsStripped() throws Exception {
        var forwardedFilter = new org.springframework.web.filter.ForwardedHeaderFilter();
        var request = request("POST", "/auth/login");
        request.setRemoteAddr("172.18.0.10");
        request.addHeader("X-Forwarded-For", "198.51.100.77");
        stubCounter("ratelimit:auth:ip:198.51.100.77", 16, 45);

        forwardedFilter.doFilter(request, response, (wrappedReq, wrappedRes) ->
                filter.doFilter(wrappedReq, wrappedRes, chain));

        verifyNoInteractions(chain);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("45");
        assertError(HttpStatus.TOO_MANY_REQUESTS, "/auth/login");
    }

    @Test
    void redisFailureAllowsRequestWhenFailOpen() throws Exception {
        var request = request("GET", "/portfolios");
        stubRedisFailure();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void redisFailureReturns503WhenFailClosed() throws Exception {
        var request = request("GET", "/portfolios");
        properties.setFailOpen(false);
        stubRedisFailure();

        filter.doFilter(request, response, chain);

        verifyNoInteractions(chain);
        assertError(HttpStatus.SERVICE_UNAVAILABLE, request.getRequestURI());
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(CLIENT_IP);
        return request;
    }

    private void authenticate(UUID userId) {
        var user = new User();
        user.setId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private void stubCounter(String key, long count, long ttl) {
        when(redisTemplate.execute(any(), eq(List.of(key)), eq(Integer.toString(properties.getWindowSeconds()))))
                .thenReturn(List.of(count, ttl));
    }

    private void stubRedisFailure() {
        when(redisTemplate.execute(any(), eq(List.of(API_IP_KEY)), eq("60")))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
    }

    private void assertBypass(RateLimitFilter rateLimitFilter, MockHttpServletRequest request) throws Exception {
        rateLimitFilter.doFilter(request, response, chain);

        verifyNoInteractions(redisTemplate);
        verify(chain).doFilter(request, response);
        assertThat(response.getHeader(LIMIT)).isNull();
        assertThat(response.getHeader(REMAINING)).isNull();
    }

    private void assertError(HttpStatus status, String path) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status.value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        var error = objectMapper.readValue(response.getContentAsByteArray(), ApiErrorResponse.class);
        assertThat(error.timestamp()).isNotNull();
        assertThat(error.status()).isEqualTo(status.value());
        assertThat(error.error()).isEqualTo(status.getReasonPhrase());
        assertThat(error.message()).isNotBlank();
        assertThat(error.path()).isEqualTo(path);
    }
}
