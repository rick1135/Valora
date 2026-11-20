package com.rick1135.Valora.security;

import tools.jackson.databind.ObjectMapper;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(RateLimitProperties.class)
@Import(RateLimitFilter.class)
public class SecurityConfigurations {
    private static final String DEFAULT_ALLOWED_ORIGINS =
            "http://localhost:3000,http://localhost:5173,http://localhost:4200";

    private final SecurityFilter securityFilter;
    private final ObjectMapper objectMapper;
    private final @Nullable RateLimitFilter rateLimitFilter;
    private final List<String> allowedOrigins;
    private final boolean hstsEnabled;

    public SecurityConfigurations(SecurityFilter securityFilter, ObjectMapper objectMapper) {
        this(securityFilter, objectMapper, new RateLimitProperties(), null,
                List.of(DEFAULT_ALLOWED_ORIGINS.split(",")), false);
    }

    @Autowired
    public SecurityConfigurations(
            SecurityFilter securityFilter,
            ObjectMapper objectMapper,
            RateLimitProperties rateLimitProperties,
            @Nullable RateLimitFilter rateLimitFilter,
            @Value("${security.cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}") List<String> allowedOrigins,
            @Value("${security.headers.hsts-enabled:false}") boolean hstsEnabled
    ) {
        this.securityFilter = securityFilter;
        this.objectMapper = objectMapper;
        this.rateLimitFilter = rateLimitFilter;
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).filter(origin -> !origin.isEmpty()).toList();
        this.hstsEnabled = hstsEnabled;
    }

    @Bean
    public @Nullable FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration() {
        if (rateLimitFilter == null) {
            return null;
        }
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; "
                                + "style-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(ref -> ref.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> {
                            if (hstsEnabled) {
                                hsts.includeSubDomains(true).maxAgeInSeconds(31536000);
                            } else {
                                hsts.disable();
                            }
                        }))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(
                        (request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            objectMapper.writeValue(
                                    response.getOutputStream(),
                                    GlobalExceptionHandler.unauthorizedResponse(request.getRequestURI())
                            );
                        }
                ))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                HttpMethod.GET,
                                "/",
                                "/index.html",
                                "/landing.css",
                                "/login.html",
                                "/cadastro.html",
                                "/auth.css",
                                "/auth.js",
                                "/api.js",
                                "/dashboard.html",
                                "/rentabilidade.html",
                                "/proventos.html",
                                "/metas.html",
                                "/metas-page.css",
                                "/proventos-page.css",
                                "/rentabilidade-page.css",
                                "/lancamentos.html",
                                "/lancamentos.js",
                                "/lancamentos-page.css",
                                "/conta.html",
                                "/conta.js",
                                "/conta-page.css",
                                "/portfolio-ui.js",
                                "/portfolio-ui.css",
                                "/styles.css",
                                "/transaction-ui.css",
                                "/transaction-ui.js",
                                "/dashboard.js",
                                "/rentabilidade.js",
                                "/proventos.js",
                                "/metas.js",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .requestMatchers("/auth/login", "/auth/register", "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus", "/actuator/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/assets").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/assets/sync").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/provents", "/provents/sync").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/benchmarks/sync").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(securityFilter, UsernamePasswordAuthenticationFilter.class);
        if (rateLimitFilter != null) {
            http.addFilterAfter(rateLimitFilter, SecurityFilter.class);
        }
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        configuration.setExposedHeaders(List.of(
                "Authorization", "X-Request-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        PasswordEncoder bcrypt = new BCryptPasswordEncoder();
        String pbkdf2Id = "pbkdf2@SpringSecurity_v5_8";
        var encoder = new DelegatingPasswordEncoder(pbkdf2Id,
                Map.of(pbkdf2Id, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        encoder.setDefaultPasswordEncoderForMatches(bcrypt);

        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                if (rawPassword.toString().getBytes(StandardCharsets.UTF_8).length <= 72) {
                    return bcrypt.encode(rawPassword);
                }
                return encoder.encode(rawPassword);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return encoder.matches(rawPassword, encodedPassword);
            }
        };
    }
}
