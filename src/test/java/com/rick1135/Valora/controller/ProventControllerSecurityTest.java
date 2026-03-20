package com.rick1135.Valora.controller;

import com.rick1135.Valora.ValoraApplication;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.ProventProvisionRepository;
import com.rick1135.Valora.repository.ProventRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import com.rick1135.Valora.repository.UserRepository;
import com.rick1135.Valora.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(classes = ValoraApplication.class)
@ActiveProfiles("test")
class ProventControllerSecurityTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private PositionRepository positionRepository;
    @Autowired
    private ProventRepository proventRepository;
    @Autowired
    private ProventProvisionRepository proventProvisionRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clearData();
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
    void createProventShouldReturnUnauthorizedWhenAuthenticationIsMissing() throws Exception {
        UUID assetId = seedAsset();

        mockMvc.perform(post("/provents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(assetId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createProventShouldReturnForbiddenWhenUserIsNotAdmin() throws Exception {
        UUID assetId = seedAsset();
        String token = seedUserAndToken(UserRole.USER);

        mockMvc.perform(post("/provents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(assetId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createProventShouldReturnCreatedForAdmin() throws Exception {
        UUID assetId = seedAsset();
        String token = seedUserAndToken(UserRole.ADMIN);

        mockMvc.perform(post("/provents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(assetId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("ITSA4"));
    }

    @Test
    void syncProventsShouldReturnOkForAdmin() throws Exception {
        String token = seedUserAndToken(UserRole.ADMIN);

        mockMvc.perform(post("/provents/sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetsScanned").value(0));
    }

    private String seedUserAndToken(UserRole role) {
        User user = new User();
        user.setEmail(role.name().toLowerCase() + "@valora.dev");
        user.setPasswordHash("hash");
        user.setName(role.name());
        user.setRole(role);
        user = userRepository.saveAndFlush(user);
        return tokenService.generateToken(user);
    }

    private UUID seedAsset() {
        Asset asset = new Asset();
        asset.setTicker("ITSA4");
        asset.setName("Itausa");
        asset.setCategory(AssetCategory.ACOES);
        return assetRepository.saveAndFlush(asset).getId();
    }

    private void clearData() {
        proventProvisionRepository.deleteAll();
        proventRepository.deleteAll();
        transactionRepository.deleteAll();
        positionRepository.deleteAll();
        assetRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String validRequestBody(UUID assetId) {
        return """
                {
                  "assetId": "%s",
                  "type": "DIVIDEND",
                  "amountPerShare": 1.23,
                  "comDate": "2026-03-20T00:00:00Z",
                  "paymentDate": "2026-03-30T00:00:00Z"
                }
                """.formatted(assetId);
    }
}
