package com.rick1135.Valora.controller;

import com.rick1135.Valora.ValoraApplication;
import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(classes = ValoraApplication.class)
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class ProventControllerSecurityTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdAssetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        List<SecurityFilterChain> securityFilterChains = new ArrayList<>(context.getBeansOfType(SecurityFilterChain.class).values());
        mockMvc = webAppContextSetup(context)
                .addFilters(new FilterChainProxy(securityFilterChains))
                .build();
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        for (UUID assetId : createdAssetIds) {
            jdbcTemplate.update("DELETE FROM provent_provisions WHERE asset_id = ?", assetId);
            jdbcTemplate.update("DELETE FROM provents WHERE asset_id = ?", assetId);
            assetRepository.deleteById(assetId);
        }
        createdAssetIds.clear();
        if (!createdUserIds.isEmpty()) {
            userRepository.deleteAllById(createdUserIds);
            createdUserIds.clear();
        }
    }

    @Test
    void createProventShouldReturnUnauthorizedWhenAuthenticationIsMissing() throws Exception {
        UUID assetId = seedAsset(uniqueTicker("PCS"));

        mockMvc.perform(post("/provents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(assetId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createProventShouldReturnForbiddenWhenUserIsNotAdmin() throws Exception {
        UUID assetId = seedAsset(uniqueTicker("PCS"));
        String token = seedUserAndToken(UserRole.USER);

        mockMvc.perform(post("/provents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(assetId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void createProventShouldReturnCreatedForAdmin() throws Exception {
        String ticker = uniqueTicker("PCS");
        UUID assetId = seedAsset(ticker);
        String token = seedUserAndToken(UserRole.ADMIN);

        mockMvc.perform(post("/provents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody(assetId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value(ticker));
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
        user.setEmail(role.name().toLowerCase() + "-prov-" + UUID.randomUUID() + "@valora.dev");
        user.setPasswordHash("hash");
        user.setName(role.name());
        user.setRole(role);
        user = userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());
        return tokenService.generateToken(user);
    }

    private UUID seedAsset(String ticker) {
        Asset asset = new Asset();
        asset.setTicker(ticker);
        asset.setName("Itausa");
        asset.setCategory(AssetCategory.ACOES);
        UUID assetId = assetRepository.saveAndFlush(asset).getId();
        createdAssetIds.add(assetId);
        return assetId;
    }

    private String validRequestBody(UUID assetId) {
        return """
                {
                  "assetId": "%s",
                  "type": "DIVIDEND",
                  "amountPerShare": 1.23,
                  "comDate": "2026-03-20",
                  "paymentDate": "2026-03-30"
                }
                """.formatted(assetId);
    }
}
