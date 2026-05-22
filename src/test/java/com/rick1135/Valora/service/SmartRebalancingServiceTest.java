package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.SuggestedOrderDTO;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioTarget;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.repository.PortfolioTargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartRebalancingServiceTest {

    @Mock
    private PortfolioService portfolioService;

    @Mock
    private PortfolioTargetRepository targetRepository;

    @InjectMocks
    private SmartRebalancingService rebalancingService;

    @Test
    void shouldCalculateSuggestedOrdersCorrectly() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(portfolioId);

        BigDecimal contribution = new BigDecimal("1000.00");
        BigDecimal currentPatrimony = new BigDecimal("9000.00");

        PortfolioSummaryDTO summary = new PortfolioSummaryDTO(
                currentPatrimony,
                new BigDecimal("8000.00"),
                BigDecimal.ZERO,
                null,
                List.of(
                        new AssetAllocationDTO("ACOES", new BigDecimal("6000.00"), new BigDecimal("66.67")),
                        new AssetAllocationDTO("FII", new BigDecimal("3000.00"), new BigDecimal("33.33"))
                ),
                false,
                List.of()
        );

        List<PortfolioTarget> targets = List.of(
                new PortfolioTarget(UUID.randomUUID(), portfolio, AssetCategory.ACOES, new BigDecimal("50.00")),
                new PortfolioTarget(UUID.randomUUID(), portfolio, AssetCategory.FII, new BigDecimal("50.00"))
        );

        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);
        when(portfolioService.getPortfolioSummary(user, portfolioId)).thenReturn(summary);
        when(targetRepository.findByPortfolio(portfolio)).thenReturn(targets);

        List<SuggestedOrderDTO> suggestions = rebalancingService.calculateSuggestedOrders(user, portfolioId, contribution);

        assertThat(suggestions).hasSize(2);
        
        SuggestedOrderDTO fiiOrder = suggestions.stream()
                .filter(o -> o.category().equals("FII"))
                .findFirst().orElseThrow();
        
        assertThat(fiiOrder.gapAmount()).isEqualByComparingTo("2000.00");
        assertThat(fiiOrder.suggestedInvestment()).isEqualByComparingTo("1000.00");
        assertThat(fiiOrder.marketPriceReliable()).isTrue();

        SuggestedOrderDTO stockOrder = suggestions.stream()
                .filter(o -> o.category().equals("ACOES"))
                .findFirst().orElseThrow();

        assertThat(stockOrder.gapAmount()).isEqualByComparingTo("-1000.00");
        assertThat(stockOrder.suggestedInvestment()).isEqualByComparingTo("0.00");
    }

    @Test
    void shouldHandleZeroContributionByExposingGaps() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(portfolioId);

        PortfolioSummaryDTO summary = new PortfolioSummaryDTO(
                new BigDecimal("10000.00"),
                new BigDecimal("8000.00"),
                BigDecimal.ZERO,
                null,
                List.of(
                        new AssetAllocationDTO("ACOES", new BigDecimal("7000.00"), new BigDecimal("70.00")),
                        new AssetAllocationDTO("FII", new BigDecimal("3000.00"), new BigDecimal("30.00"))
                ),
                false,
                List.of()
        );

        List<PortfolioTarget> targets = List.of(
                new PortfolioTarget(UUID.randomUUID(), portfolio, AssetCategory.ACOES, new BigDecimal("50.00")),
                new PortfolioTarget(UUID.randomUUID(), portfolio, AssetCategory.FII, new BigDecimal("50.00"))
        );

        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);
        when(portfolioService.getPortfolioSummary(user, portfolioId)).thenReturn(summary);
        when(targetRepository.findByPortfolio(portfolio)).thenReturn(targets);

        List<SuggestedOrderDTO> suggestions = rebalancingService.calculateSuggestedOrders(user, portfolioId, BigDecimal.ZERO);

        SuggestedOrderDTO fiiOrder = suggestions.stream().filter(o -> o.category().equals("FII")).findFirst().get();
        assertThat(fiiOrder.gapAmount()).isEqualByComparingTo("2000.00"); // 5000 - 3000
        assertThat(fiiOrder.suggestedInvestment()).isEqualByComparingTo("0.00");

        SuggestedOrderDTO stockOrder = suggestions.stream().filter(o -> o.category().equals("ACOES")).findFirst().get();
        assertThat(stockOrder.gapAmount()).isEqualByComparingTo("-2000.00"); // 5000 - 7000
        assertThat(stockOrder.suggestedInvestment()).isEqualByComparingTo("0.00");
    }

    @Test
    void shouldAcceptTargetsSummingApproximately100() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);

        Map<String, BigDecimal> validTargets = Map.of(
                "ACOES", new BigDecimal("33.33"),
                "FII", new BigDecimal("33.33"),
                "RENDA_FIXA", new BigDecimal("33.33")
        ); // Total 99.99

        assertThatCode(() -> rebalancingService.saveTargets(user, portfolioId, validTargets))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldThrowExceptionWhenTargetsAreWayOff100() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);

        Map<String, BigDecimal> invalidTargets = Map.of(
                "ACOES", new BigDecimal("50.00"),
                "FII", new BigDecimal("40.00")
        );

        assertThatThrownBy(() -> rebalancingService.saveTargets(user, portfolioId, invalidTargets))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A soma das metas deve ser aproximadamente 100%");
    }
}
