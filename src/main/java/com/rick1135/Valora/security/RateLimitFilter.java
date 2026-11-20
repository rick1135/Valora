package com.rick1135.Valora.security;

import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Set<String> AUTH_PATHS = Set.of("/auth/login", "/auth/register", "/auth/refresh");
    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/", "/index.html", "/favicon.ico", "/actuator/health",
            "/actuator/health/liveness", "/actuator/health/readiness"
    );

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> COUNTER_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then ttl = tonumber(ARGV[1]) end
            return {current, ttl}
            """, List.class);

    private final @Nullable StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(@Nullable StringRedisTemplate redisTemplate, RateLimitProperties properties,
                           ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || redisTemplate == null || HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (EXCLUDED_PATHS.contains(path) || path.endsWith(".html") || path.endsWith(".css") || path.endsWith(".js")) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean authPath = AUTH_PATHS.contains(path);
        String key = authPath ? "ratelimit:auth:ip:" + request.getRemoteAddr() : apiKey(request);
        int limit = authPath ? properties.getAuthRequestsPerMinute() : properties.getApiRequestsPerMinute();
        long count;
        long ttl;
        try {
            List<?> result = redisTemplate.execute(COUNTER_SCRIPT, List.of(key), Integer.toString(properties.getWindowSeconds()));
            if (result == null || result.size() != 2
                    || !(result.get(0) instanceof Number current) || !(result.get(1) instanceof Number seconds)) {
                throw new IllegalStateException("Unexpected Redis rate-limit counter result.");
            }
            count = current.longValue();
            ttl = seconds.longValue();
        } catch (RuntimeException exception) {
            log.warn("Could not evaluate the rate limit in Redis.", exception);
            if (properties.isFailOpen()) {
                filterChain.doFilter(request, response);
            } else {
                writeError(request, response, HttpStatus.SERVICE_UNAVAILABLE, "Servico temporariamente indisponivel.");
            }
            return;
        }

        response.setIntHeader("X-RateLimit-Limit", limit);
        response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(0, limit - count)));
        if (count > limit) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, ttl)));
            writeError(request, response, HttpStatus.TOO_MANY_REQUESTS, "Limite de requisicoes excedido.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String apiKey(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return "ratelimit:api:user:" + user.getId();
        }
        return "ratelimit:api:ip:" + request.getRemoteAddr();
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                OffsetDateTime.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI()
        ));
    }
}
