package com.rick1135.Valora.controller;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.exception.InvalidTokenException;
import com.rick1135.Valora.repository.UserRepository;
import com.rick1135.Valora.security.SecurityConfigurations;
import com.rick1135.Valora.security.SecurityFilter;
import com.rick1135.Valora.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccessTokenSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        reset(tokenService, userRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void dashboardStaticFrontendShouldBePublic() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/landing.css"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/login.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/cadastro.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/auth.css"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/auth.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/dashboard.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/rentabilidade.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/proventos.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/metas.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/metas-page.css")).andExpect(status().isOk());
        mockMvc.perform(get("/proventos-page.css")).andExpect(status().isOk());
        mockMvc.perform(get("/rentabilidade-page.css")).andExpect(status().isOk());
        mockMvc.perform(get("/lancamentos.html")).andExpect(status().isOk());
        mockMvc.perform(get("/lancamentos.js")).andExpect(status().isOk());
        mockMvc.perform(get("/lancamentos-page.css")).andExpect(status().isOk());
        mockMvc.perform(get("/portfolio-ui.js")).andExpect(status().isOk());
        mockMvc.perform(get("/portfolio-ui.css")).andExpect(status().isOk());
        mockMvc.perform(get("/styles.css"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/dashboard.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/rentabilidade.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/proventos.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/metas.js"))
                .andExpect(status().isOk());
    }

    @Test
    void accountStaticFrontendShouldBePublic() throws Exception {
        mockMvc.perform(get("/conta.html")).andExpect(status().isOk());
        mockMvc.perform(get("/conta.js")).andExpect(status().isOk());
        mockMvc.perform(get("/conta-page.css")).andExpect(status().isOk());
    }

    @Test
    void accountProfileShouldReturnUnauthorizedWhenAuthenticationIsMissing() throws Exception {
        assertGenericUnauthorized(mockMvc.perform(get("/account/me")), null);
    }

    @Test
    void passwordEncoderSupportsLongPasswordsAndExistingBcryptHashes() {
        String rawPassword = "a".repeat(127) + "b";
        String encodedPassword = passwordEncoder.encode(rawPassword);
        String existingHash = new BCryptPasswordEncoder().encode("existing-password");

        assertThat(passwordEncoder.matches(rawPassword, encodedPassword)).isTrue();
        assertThat(passwordEncoder.matches("a".repeat(127) + "c", encodedPassword)).isFalse();
        assertThat(passwordEncoder.matches("existing-password", existingHash)).isTrue();
        assertThat(passwordEncoder.encode("short-password")).startsWith("$2");
    }

    @Test
    void swaggerAndLogoutEndpointsShouldBePublic() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh-token\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void globalAssetReadShouldReturnUnauthorizedWithGenericBodyWhenAuthenticationIsMissing() throws Exception {
        assertGenericUnauthorized(mockMvc.perform(get("/assets")), null);
    }

    @Test
    void globalAssetReadShouldReturnUnauthorizedWithGenericBodyForMalformedAuthorizationHeader() throws Exception {
        assertGenericUnauthorized(
                mockMvc.perform(get("/assets").header("Authorization", "Basic abc")),
                "Basic abc"
        );
    }

    @Test
    void globalAssetReadShouldReturnUnauthorizedWithGenericBodyForBlankBearerToken() throws Exception {
        assertGenericUnauthorized(
                mockMvc.perform(get("/assets").header("Authorization", "Bearer   ")),
                "Bearer"
        );
    }

    @Test
    void globalAssetReadShouldReturnUnauthorizedWithGenericBodyForInvalidToken() throws Exception {
        String token = "not-a-jwt";
        when(tokenService.validateToken(token))
                .thenThrow(new InvalidTokenException("Token invalido ou expirado."));

        assertGenericUnauthorized(
                mockMvc.perform(get("/assets").header("Authorization", "Bearer " + token)),
                token
        );
    }

    @Test
    void globalAssetReadShouldReturnUnauthorizedWithGenericBodyForUnknownUserToken() throws Exception {
        String token = "valid-missing-user-token";
        when(tokenService.validateToken(token)).thenReturn("missing@valora.dev");
        when(userRepository.findByEmail("missing@valora.dev")).thenReturn(Optional.empty());

        assertGenericUnauthorized(
                mockMvc.perform(get("/assets").header("Authorization", "Bearer " + token)),
                token
        );
    }

    @Test
    void globalAssetReadShouldReturnOkForExistingUserToken() throws Exception {
        mockMvc.perform(get("/assets").with(user("user@valora.dev").roles("USER")))
                .andExpect(status().isOk());
    }

    @Test
    void ownerPortfolioReadShouldReturnUnauthorizedWithGenericBodyWhenAuthenticationIsMissing() throws Exception {
        assertGenericUnauthorized(mockMvc.perform(get("/portfolios/{portfolioId}", UUID.randomUUID())), null);
    }

    @Test
    void ownerPortfolioReadShouldReturnUnauthorizedWithGenericBodyForInvalidToken() throws Exception {
        String token = "not-a-jwt";
        when(tokenService.validateToken(token))
                .thenThrow(new InvalidTokenException("Token invalido ou expirado."));

        assertGenericUnauthorized(
                mockMvc.perform(get("/portfolios/{portfolioId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)),
                token
        );
    }

    @Test
    void ownerPortfolioReadShouldReturnUnauthorizedWithGenericBodyForUnknownUserToken() throws Exception {
        String token = "valid-missing-user-token";
        when(tokenService.validateToken(token)).thenReturn("missing@valora.dev");
        when(userRepository.findByEmail("missing@valora.dev")).thenReturn(Optional.empty());

        assertGenericUnauthorized(
                mockMvc.perform(get("/portfolios/{portfolioId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)),
                token
        );
    }

    @Test
    void ownerPortfolioReadShouldReturnOkForOwnerToken() throws Exception {
        UUID portfolioId = UUID.randomUUID();

        mockMvc.perform(get("/portfolios/{portfolioId}", portfolioId)
                        .with(user("owner@valora.dev").roles("USER")))
                .andExpect(status().isOk());
    }

    @Test
    void adminAssetMutationShouldReturnUnauthorizedWithGenericBodyWhenAuthenticationIsMissing() throws Exception {
        assertGenericUnauthorized(
                mockMvc.perform(post("/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAssetRequestBody())),
                null
        );
    }

    @Test
    void adminAssetMutationShouldReturnUnauthorizedWithGenericBodyForInvalidToken() throws Exception {
        String token = "not-a-jwt";
        when(tokenService.validateToken(token))
                .thenThrow(new InvalidTokenException("Token invalido ou expirado."));

        assertGenericUnauthorized(
                mockMvc.perform(post("/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAssetRequestBody())),
                token
        );
    }

    @Test
    void adminAssetMutationShouldReturnUnauthorizedWithGenericBodyForUnknownUserToken() throws Exception {
        String token = "valid-missing-admin-token";
        when(tokenService.validateToken(token)).thenReturn("missing-admin@valora.dev");
        when(userRepository.findByEmail("missing-admin@valora.dev")).thenReturn(Optional.empty());

        assertGenericUnauthorized(
                mockMvc.perform(post("/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAssetRequestBody())),
                token
        );
    }

    @Test
    void adminAssetMutationShouldReturnCreatedForAdminToken() throws Exception {
        mockMvc.perform(post("/assets")
                        .with(user("admin@valora.dev").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAssetRequestBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void benchmarkSyncShouldRequireAdminRoleAndStatusShouldRequireAuthentication() throws Exception {
        assertGenericUnauthorized(mockMvc.perform(get("/benchmarks/status")), null);
        mockMvc.perform(get("/benchmarks/status")
                        .with(user("investor@valora.dev").roles("USER")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/benchmarks/sync")
                        .with(user("investor@valora.dev").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/benchmarks/sync")
                        .with(user("admin@valora.dev").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    private void assertGenericUnauthorized(ResultActions resultActions, String sensitiveValue) throws Exception {
        resultActions
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Token invalido ou expirado."))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body)
                            .doesNotContain("Exception")
                            .doesNotContain("JWT")
                            .doesNotContain("stackTrace")
                            .doesNotContain("trace");
                    if (sensitiveValue != null) {
                        assertThat(body).doesNotContain(sensitiveValue);
                    }
                });
    }

    private String validAssetRequestBody() {
        return """
                {
                  "ticker": "PETR4",
                  "name": "Petrobras",
                  "category": "ACOES"
                }
                """;
    }

    @SpringBootConfiguration
    @Import({
            SecurityConfigurations.class,
            SecurityFilter.class,
            GlobalExceptionHandler.class,
            TestControllers.class,
            TestBeans.class
    })
    static class TestApplication {
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        TokenService tokenService() {
            return Mockito.mock(TokenService.class);
        }

        @Bean
        @Primary
        UserRepository userRepository() {
            return Mockito.mock(UserRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().findAndAddModules().build();
        }

        @Bean("handlerExceptionResolver")
        HandlerExceptionResolver handlerExceptionResolver(ObjectMapper objectMapper) {
            return (request, response, handler, exception) -> {
                response.setStatus(401);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                try {
                    objectMapper.writeValue(
                            response.getOutputStream(),
                            GlobalExceptionHandler.unauthorizedResponse(request.getRequestURI())
                    );
                } catch (Exception writeException) {
                    throw new IllegalStateException("Could not write test unauthorized response.", writeException);
                }
                return new ModelAndView();
            };
        }

        @Bean
        FilterRegistrationBean<SecurityFilter> securityFilterRegistration(SecurityFilter securityFilter) {
            FilterRegistrationBean<SecurityFilter> registration = new FilterRegistrationBean<>(securityFilter);
            registration.setOrder(-100);
            return registration;
        }
    }

    @RestController
    static class TestControllers {
        @GetMapping("/assets")
        void assets() {
        }

        @GetMapping({
                "/",
                "/index.html",
                "/landing.css",
                "/login.html",
                "/cadastro.html",
                "/auth.css",
                "/auth.js",
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
                "/dashboard.js",
                "/rentabilidade.js",
                "/proventos.js",
                "/metas.js"
        })
        void dashboardStaticFrontend() {
        }

        @GetMapping("/account/me")
        void accountProfile() {
        }

        @GetMapping("/portfolios/{portfolioId}")
        void portfolio(@PathVariable UUID portfolioId) {
        }

        @PostMapping("/assets")
        @ResponseStatus(CREATED)
        void createAsset() {
        }

        @GetMapping({
                "/swagger-ui.html",
                "/swagger-ui/index.html",
                "/v3/api-docs",
                "/v3/api-docs/swagger-config"
        })
        void swaggerDocs() {
        }

        @PostMapping("/auth/logout")
        @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
        void logout() {
        }

        @GetMapping("/benchmarks/status")
        void benchmarkStatus() {
        }

        @PostMapping("/benchmarks/sync")
        void benchmarkSync() {
        }
    }
}
