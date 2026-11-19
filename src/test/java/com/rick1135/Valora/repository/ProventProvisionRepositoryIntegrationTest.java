package com.rick1135.Valora.repository;

import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.entity.*;
import com.rick1135.Valora.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class ProventProvisionRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ProventProvisionRepository proventProvisionRepository;

    @Autowired
    private ProventRepository proventRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private UserRepository userRepository;

    private final List<UUID> createdProvisionIds = new ArrayList<>();
    private final List<UUID> createdProventIds = new ArrayList<>();
    private final List<UUID> createdPortfolioIds = new ArrayList<>();
    private final List<UUID> createdAssetIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (!createdProvisionIds.isEmpty()) {
            proventProvisionRepository.deleteAllById(createdProvisionIds);
            createdProvisionIds.clear();
        }
        if (!createdProventIds.isEmpty()) {
            proventRepository.deleteAllById(createdProventIds);
            createdProventIds.clear();
        }
        if (!createdPortfolioIds.isEmpty()) {
            portfolioRepository.deleteAllById(createdPortfolioIds);
            createdPortfolioIds.clear();
        }
        if (!createdAssetIds.isEmpty()) {
            assetRepository.deleteAllById(createdAssetIds);
            createdAssetIds.clear();
        }
        if (!createdUserIds.isEmpty()) {
            userRepository.deleteAllById(createdUserIds);
            createdUserIds.clear();
        }
    }

    @Test
    void shouldUpdateOnlyPastOrTodayProventsAndReturnCount() {
        // Given
        String uniqueSuffix = UUID.randomUUID().toString();

        User user = new User();
        user.setEmail("ppr-" + uniqueSuffix + "@test.com");
        user.setName("Test User");
        user.setPasswordHash("hash");
        user.setRole(UserRole.USER);
        user = userRepository.saveAndFlush(user);
        createdUserIds.add(user.getId());

        Asset asset = new Asset();
        asset.setTicker(uniqueTicker("PPR"));
        asset.setName("Petrobras");
        asset.setCategory(AssetCategory.ACOES);
        asset = assetRepository.saveAndFlush(asset);
        createdAssetIds.add(asset.getId());

        Portfolio portfolio = new Portfolio();
        portfolio.setUser(user);
        portfolio.setName("Main Portfolio");
        portfolio.setDescription("Test");
        portfolio = portfolioRepository.saveAndFlush(portfolio);
        createdPortfolioIds.add(portfolio.getId());

        LocalDate today = LocalDate.of(2026, 3, 20);
        LocalDate past = today.minusDays(1);
        LocalDate future = today.plusDays(1);

        Provent pastProvent = new Provent();
        pastProvent.setAsset(asset);
        pastProvent.setType(ProventType.DIVIDEND);
        pastProvent.setAmountPerShare(new BigDecimal("1.0"));
        pastProvent.setOriginSource(ProventSource.BRAPI);
        pastProvent.setOriginEventKey("evt_past_" + uniqueSuffix);
        pastProvent.setOriginRate(new BigDecimal("1.0"));
        pastProvent.setOriginRateBasis(ProventRateBasis.NET);
        pastProvent.setComDate(past.minusDays(5));
        pastProvent.setPaymentDate(past);
        pastProvent = proventRepository.saveAndFlush(pastProvent);
        createdProventIds.add(pastProvent.getId());

        Provent futureProvent = new Provent();
        futureProvent.setAsset(asset);
        futureProvent.setType(ProventType.DIVIDEND);
        futureProvent.setAmountPerShare(new BigDecimal("1.0"));
        futureProvent.setOriginSource(ProventSource.BRAPI);
        futureProvent.setOriginEventKey("evt_future_" + uniqueSuffix);
        futureProvent.setOriginRate(new BigDecimal("1.0"));
        futureProvent.setOriginRateBasis(ProventRateBasis.NET);
        futureProvent.setComDate(past);
        futureProvent.setPaymentDate(future);
        futureProvent = proventRepository.saveAndFlush(futureProvent);
        createdProventIds.add(futureProvent.getId());

        ProventProvision pastProvision = new ProventProvision();
        pastProvision.setProvent(pastProvent);
        pastProvision.setPortfolio(portfolio);
        pastProvision.setAsset(asset);
        pastProvision.setQuantityOnComDate(new BigDecimal("100"));
        pastProvision.setGrossAmount(new BigDecimal("100"));
        pastProvision.setWithholdingTaxAmount(BigDecimal.ZERO);
        pastProvision.setNetAmount(new BigDecimal("100"));
        pastProvision.setStatus(ProventStatus.PENDING);
        pastProvision = proventProvisionRepository.saveAndFlush(pastProvision);
        createdProvisionIds.add(pastProvision.getId());

        ProventProvision futureProvision = new ProventProvision();
        futureProvision.setProvent(futureProvent);
        futureProvision.setPortfolio(portfolio);
        futureProvision.setAsset(asset);
        futureProvision.setQuantityOnComDate(new BigDecimal("100"));
        futureProvision.setGrossAmount(new BigDecimal("100"));
        futureProvision.setWithholdingTaxAmount(BigDecimal.ZERO);
        futureProvision.setNetAmount(new BigDecimal("100"));
        futureProvision.setStatus(ProventStatus.PENDING);
        futureProvision = proventProvisionRepository.saveAndFlush(futureProvision);
        createdProvisionIds.add(futureProvision.getId());

        // When
        int updated = proventProvisionRepository.updatePendingToPaid(today);

        // Then
        assertThat(updated).isEqualTo(1);

        List<ProventProvision> provisions = proventProvisionRepository.findAllById(createdProvisionIds);
        assertThat(provisions).hasSize(2);

        UUID pastProvisionId = pastProvision.getId();
        ProventProvision updatedPastProvision = provisions.stream()
                .filter(p -> p.getId().equals(pastProvisionId))
                .findFirst()
                .orElseThrow();
        assertThat(updatedPastProvision.getStatus()).isEqualTo(ProventStatus.PAID);

        UUID futureProvisionId = futureProvision.getId();
        ProventProvision notUpdatedFutureProvision = provisions.stream()
                .filter(p -> p.getId().equals(futureProvisionId))
                .findFirst()
                .orElseThrow();
        assertThat(notUpdatedFutureProvision.getStatus()).isEqualTo(ProventStatus.PENDING);
    }

    @Test
    void shouldReturnZeroWhenNoProvisionsExist() {
        int updated = proventProvisionRepository.updatePendingToPaid(LocalDate.of(2026, 3, 20));
        assertThat(updated).isEqualTo(0);
    }
}
