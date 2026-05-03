package com.rick1135.Valora.repository;

import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.entity.*;
import com.rick1135.Valora.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    @BeforeEach
    void setUp() {
        proventProvisionRepository.deleteAll();
        proventRepository.deleteAll();
        portfolioRepository.deleteAll();
        assetRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void shouldUpdateOnlyPastOrTodayProventsAndReturnCount() {
        // Given
        User user = new User();
        user.setEmail("test@test.com");
        user.setName("Test User");
        user.setPasswordHash("hash");
        user.setRole(UserRole.USER);
        userRepository.saveAndFlush(user);

        Asset asset = new Asset();
        asset.setTicker("PETR4");
        asset.setName("Petrobras");
        asset.setCategory(AssetCategory.ACOES);
        assetRepository.saveAndFlush(asset);

        Portfolio portfolio = new Portfolio();
        portfolio.setUser(user);
        portfolio.setName("Main Portfolio");
        portfolio.setDescription("Test");
        portfolioRepository.saveAndFlush(portfolio);

        LocalDate today = LocalDate.of(2026, 3, 20);
        LocalDate past = today.minusDays(1);
        LocalDate future = today.plusDays(1);

        Provent pastProvent = new Provent();
        pastProvent.setAsset(asset);
        pastProvent.setType(ProventType.DIVIDEND);
        pastProvent.setAmountPerShare(new BigDecimal("1.0"));
        pastProvent.setOriginSource(ProventSource.BRAPI);
        pastProvent.setOriginEventKey("evt_past");
        pastProvent.setComDate(past.minusDays(5));
        pastProvent.setPaymentDate(past);
        proventRepository.saveAndFlush(pastProvent);

        Provent futureProvent = new Provent();
        futureProvent.setAsset(asset);
        futureProvent.setType(ProventType.DIVIDEND);
        futureProvent.setAmountPerShare(new BigDecimal("1.0"));
        futureProvent.setOriginSource(ProventSource.BRAPI);
        futureProvent.setOriginEventKey("evt_future");
        futureProvent.setComDate(past);
        futureProvent.setPaymentDate(future);
        proventRepository.saveAndFlush(futureProvent);

        ProventProvision pastProvision = new ProventProvision();
        pastProvision.setProvent(pastProvent);
        pastProvision.setPortfolio(portfolio);
        pastProvision.setAsset(asset);
        pastProvision.setQuantityOnComDate(new BigDecimal("100"));
        pastProvision.setGrossAmount(new BigDecimal("100"));
        pastProvision.setWithholdingTaxAmount(BigDecimal.ZERO);
        pastProvision.setNetAmount(new BigDecimal("100"));
        pastProvision.setStatus(ProventStatus.PENDING);
        proventProvisionRepository.saveAndFlush(pastProvision);

        ProventProvision futureProvision = new ProventProvision();
        futureProvision.setProvent(futureProvent);
        futureProvision.setPortfolio(portfolio);
        futureProvision.setAsset(asset);
        futureProvision.setQuantityOnComDate(new BigDecimal("100"));
        futureProvision.setGrossAmount(new BigDecimal("100"));
        futureProvision.setWithholdingTaxAmount(BigDecimal.ZERO);
        futureProvision.setNetAmount(new BigDecimal("100"));
        futureProvision.setStatus(ProventStatus.PENDING);
        proventProvisionRepository.saveAndFlush(futureProvision);

        // When
        int updated = proventProvisionRepository.updatePendingToPaid(today);

        // Then
        assertThat(updated).isEqualTo(1);

        List<ProventProvision> provisions = proventProvisionRepository.findAll();
        assertThat(provisions).hasSize(2);

        ProventProvision updatedPastProvision = provisions.stream()
                .filter(p -> p.getId().equals(pastProvision.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(updatedPastProvision.getStatus()).isEqualTo(ProventStatus.PAID);

        ProventProvision notUpdatedFutureProvision = provisions.stream()
                .filter(p -> p.getId().equals(futureProvision.getId()))
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
