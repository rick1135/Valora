package com.rick1135.Valora.controller;

import com.rick1135.Valora.ValoraApplication;
import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.repository.UserRepository;
import com.rick1135.Valora.service.TokenService;
import com.rick1135.Valora.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(classes = ValoraApplication.class)
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class AssetControllerSecurityTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AssetRepository assetRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        assetRepository.deleteAll();
        userRepository.deleteAll();
        List<SecurityFilterChain> securityFilterChains = new ArrayList<>(context.getBeansOfType(SecurityFilterChain.class).values());
        mockMvc = webAppContextSetup(context)
                .addFilters(new FilterChainProxy(securityFilterChains))
                .build();
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void createAssetShouldReturnUnauthorizedWhenAuthenticationIsMissing() throws Exception {
        mockMvc.perform(post("/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createAssetShouldReturnForbiddenWhenUserIsNotAdmin() throws Exception {
        String token = seedUserAndToken(UserRole.USER);

        mockMvc.perform(post("/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createAssetShouldReturnCreatedForAdmin() throws Exception {
        String token = seedUserAndToken(UserRole.ADMIN);

        mockMvc.perform(post("/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("PETR4"));
    }

    private String seedUserAndToken(UserRole role) {
        User user = new User();
        user.setEmail(role.name().toLowerCase() + "-asset@valora.dev");
        user.setPasswordHash("hash");
        user.setName(role.name());
        user.setRole(role);
        user = userRepository.saveAndFlush(user);
        return tokenService.generateToken(user);
    }

    private String validRequestBody() {
        return """
                {
                  "ticker": "PETR4",
                  "name": "Petrobras",
                  "category": "ACOES"
                }
                """;
    }
}
