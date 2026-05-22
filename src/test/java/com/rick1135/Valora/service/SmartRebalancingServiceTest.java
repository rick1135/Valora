package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.AssetPositionSummaryDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.dto.response.SuggestedOrderDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioTarget;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.repository.PortfolioTargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private QuoteService quoteService;

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
                        new AssetAllocationDTO("ACOES", new BigDecimal("6000.00"), new BigDecimal("66.67"), new BigDecimal("50.00"), true),
                        new AssetAllocationDTO("FII", new BigDecimal("3000.00"), new BigDecimal("33.33"), new BigDecimal("210.00"), true)
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
        assertThat(fiiOrder.currentPrice()).isEqualByComparingTo("210.00");
        assertThat(fiiOrder.suggestedQuantity()).isNull();
        assertThat(fiiOrder.actualInvestmentAmount()).isNull();
        assertThat(fiiOrder.residualCashAmount()).isNull();
        assertThat(fiiOrder.marketPriceReliable()).isTrue();

        SuggestedOrderDTO stockOrder = suggestions.stream()
                .filter(o -> o.category().equals("ACOES"))
                .findFirst().orElseThrow();

        assertThat(stockOrder.gapAmount()).isEqualByComparingTo("-1000.00");
        assertThat(stockOrder.suggestedInvestment()).isEqualByComparingTo("0.00");
        assertThat(stockOrder.suggestedQuantity()).isNull();
        assertThat(stockOrder.actualInvestmentAmount()).isNull();
        assertThat(stockOrder.residualCashAmount()).isNull();
    }

    @Test
    void shouldExposeResidualCashWhenContributionIsLowerThanCategoryReferencePrice() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(portfolioId);

        PortfolioSummaryDTO summary = new PortfolioSummaryDTO(
                new BigDecimal("1000.00"),
                new BigDecimal("1000.00"),
                BigDecimal.ZERO,
                null,
                List.of(
                        new AssetAllocationDTO("ACOES", new BigDecimal("1000.00"), new BigDecimal("100.00"), new BigDecimal("80.00"), true)
                ),
                false,
                List.of()
        );

        List<PortfolioTarget> targets = List.of(
                new PortfolioTarget(UUID.randomUUID(), portfolio, AssetCategory.ACOES, new BigDecimal("100.00"))
        );

        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);
        when(portfolioService.getPortfolioSummary(user, portfolioId)).thenReturn(summary);
        when(targetRepository.findByPortfolio(portfolio)).thenReturn(targets);

        List<SuggestedOrderDTO> suggestions = rebalancingService.calculateSuggestedOrders(user, portfolioId, new BigDecimal("50.00"));

        SuggestedOrderDTO stockOrder = suggestions.getFirst();
        assertThat(stockOrder.suggestedInvestment()).isEqualByComparingTo("50.00");
        assertThat(stockOrder.currentPrice()).isEqualByComparingTo("80.00");
        assertThat(stockOrder.suggestedQuantity()).isNull();
        assertThat(stockOrder.actualInvestmentAmount()).isNull();
        assertThat(stockOrder.residualCashAmount()).isNull();
    }

    @Test
    void shouldKeepMarketPriceUnreliableWhenSuggestedQuantityUsesFallbackPrice() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(portfolioId);

        PortfolioSummaryDTO summary = new PortfolioSummaryDTO(
                new BigDecimal("9000.00"),
                new BigDecimal("8000.00"),
                BigDecimal.ZERO,
                null,
                List.of(
                        new AssetAllocationDTO("ACOES", new BigDecimal("6000.00"), new BigDecimal("66.67"), new BigDecimal("50.00"), true),
                        new AssetAllocationDTO("FII", new BigDecimal("3000.00"), new BigDecimal("33.33"), new BigDecimal("210.00"), false)
                ),
                true,
                List.of("KNRI11")
        );

        List<PortfolioTarget> targets = List.of(
                new PortfolioTarget(UUID.randomUUID(), portfolio, AssetCategory.ACOES, new BigDecimal("50.00")),
                new PortfolioTarget(UUID.randomUUID(), portfolio, AssetCategory.FII, new BigDecimal("50.00"))
        );

        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);
        when(portfolioService.getPortfolioSummary(user, portfolioId)).thenReturn(summary);
        when(targetRepository.findByPortfolio(portfolio)).thenReturn(targets);

        List<SuggestedOrderDTO> suggestions = rebalancingService.calculateSuggestedOrders(user, portfolioId, new BigDecimal("1000.00"));

        SuggestedOrderDTO fiiOrder = suggestions.stream()
                .filter(o -> o.category().equals("FII"))
                .findFirst().orElseThrow();

        assertThat(fiiOrder.currentPrice()).isEqualByComparingTo("210.00");
        assertThat(fiiOrder.suggestedInvestment()).isEqualByComparingTo("1000.00");
        assertThat(fiiOrder.suggestedQuantity()).isNull();
        assertThat(fiiOrder.actualInvestmentAmount()).isNull();
        assertThat(fiiOrder.residualCashAmount()).isNull();
        assertThat(fiiOrder.marketPriceReliable()).isFalse();
    }

    @Test
    void shouldUseQuoteServiceForSpecificTickerTargetWithoutCurrentPosition() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(portfolioId);

        Asset wege = asset(UUID.randomUUID(), "WEGE3", AssetCategory.ACOES);
        PortfolioSummaryDTO summary = new PortfolioSummaryDTO(
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                BigDecimal.ZERO,
                null,
                List.of(),
                List.of(),
                false,
                List.of()
        );

        PortfolioTarget wegeTarget = assetTarget(portfolio, wege, new BigDecimal("100.00"));

        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);
        when(portfolioService.getPortfolioSummary(user, portfolioId)).thenReturn(summary);
        when(targetRepository.findByPortfolio(portfolio)).thenReturn(List.of(wegeTarget));
        when(quoteService.getCurrentQuote("WEGE3"))
                .thenReturn(Optional.of(new QuoteDTO("WEGE3", new BigDecimal("80.00"), null, null, null, null, null)));

        List<SuggestedOrderDTO> suggestions = rebalancingService.calculateSuggestedOrders(user, portfolioId, new BigDecimal("1000.00"));

        SuggestedOrderDTO wegeOrder = suggestions.getFirst();
        assertThat(wegeOrder.assetId()).isEqualTo(wege.getId());
        assertThat(wegeOrder.ticker()).isEqualTo("WEGE3");
        assertThat(wegeOrder.currentPrice()).isEqualByComparingTo("80.00");
        assertThat(wegeOrder.marketPriceReliable()).isTrue();
        assertThat(wegeOrder.suggestedQuantity()).isEqualTo(12L);
        assertThat(wegeOrder.actualInvestmentAmount()).isEqualByComparingTo("960.00");
        assertThat(wegeOrder.residualCashAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void shouldMarkSpecificTickerTargetAsUnreliableWhenQuoteFallbackWasUsed() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(portfolioId);

        Asset wege = asset(UUID.randomUUID(), "WEGE3", AssetCategory.ACOES);
        PortfolioSummaryDTO summary = new PortfolioSummaryDTO(
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                BigDecimal.ZERO,
                null,
                List.of(new AssetAllocationDTO("ACOES", new BigDecimal("2000.00"), new BigDecimal("20.00"), new BigDecimal("80.00"), false)),
                List.of(new AssetPositionSummaryDTO(wege.getId(), "WEGE3", "ACOES", new BigDecimal("2000.00"), new BigDecimal("25.00"), new BigDecimal("80.00"), false)),
                true,
                List.of("WEGE3")
        );

        PortfolioTarget wegeTarget = assetTarget(portfolio, wege, new BigDecimal("100.00"));

        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);
        when(portfolioService.getPortfolioSummary(user, portfolioId)).thenReturn(summary);
        when(targetRepository.findByPortfolio(portfolio)).thenReturn(List.of(wegeTarget));

        List<SuggestedOrderDTO> suggestions = rebalancingService.calculateSuggestedOrders(user, portfolioId, new BigDecimal("1000.00"));

        SuggestedOrderDTO wegeOrder = suggestions.getFirst();
        assertThat(wegeOrder.marketPriceReliable()).isFalse();
        assertThat(wegeOrder.currentPrice()).isEqualByComparingTo("80.00");
        assertThat(wegeOrder.suggestedQuantity()).isEqualTo(12L);
    }

    @Test
    void shouldCalculateSuggestedQuantityForSpecificTickerTargetInPolymorphicTargets() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        portfolio.setId(portfolioId);

        Asset wege = asset(UUID.randomUUID(), "WEGE3", AssetCategory.ACOES);
        Asset petr = asset(UUID.randomUUID(), "PETR4", AssetCategory.ACOES);

        PortfolioSummaryDTO summary = new PortfolioSummaryDTO(
                new BigDecimal("10000.00"),
                new BigDecimal("9000.00"),
                BigDecimal.ZERO,
                null,
                List.of(
                        new AssetAllocationDTO("FII", new BigDecimal("4400.00"), new BigDecimal("44.00"), new BigDecimal("100.00"), true),
                        new AssetAllocationDTO("ACOES", new BigDecimal("5600.00"), new BigDecimal("56.00"), new BigDecimal("40.00"), true)
                ),
                List.of(
                        new AssetPositionSummaryDTO(wege.getId(), "WEGE3", "ACOES", new BigDecimal("2000.00"), new BigDecimal("25.00"), new BigDecimal("80.00"), true),
                        new AssetPositionSummaryDTO(petr.getId(), "PETR4", "ACOES", new BigDecimal("3600.00"), new BigDecimal("120.00"), new BigDecimal("30.00"), true)
                ),
                false,
                List.of()
        );

        PortfolioTarget fiiTarget = new PortfolioTarget(UUID.randomUUID(), portfolio, AssetCategory.FII, new BigDecimal("40.00"));
        PortfolioTarget wegeTarget = assetTarget(portfolio, wege, new BigDecimal("30.00"));
        PortfolioTarget petrTarget = assetTarget(portfolio, petr, new BigDecimal("30.00"));

        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);
        when(portfolioService.getPortfolioSummary(user, portfolioId)).thenReturn(summary);
        when(targetRepository.findByPortfolio(portfolio)).thenReturn(List.of(fiiTarget, wegeTarget, petrTarget));

        List<SuggestedOrderDTO> suggestions = rebalancingService.calculateSuggestedOrders(user, portfolioId, new BigDecimal("1000.00"));

        SuggestedOrderDTO wegeOrder = suggestions.stream()
                .filter(order -> wege.getId().equals(order.assetId()))
                .findFirst().orElseThrow();

        assertThat(wegeOrder.category()).isEqualTo("ACOES");
        assertThat(wegeOrder.ticker()).isEqualTo("WEGE3");
        assertThat(wegeOrder.currentPrice()).isEqualByComparingTo("80.00");
        assertThat(wegeOrder.gapAmount()).isEqualByComparingTo("1300.00");
        assertThat(wegeOrder.suggestedInvestment()).isEqualByComparingTo("1000.00");
        assertThat(wegeOrder.suggestedQuantity()).isEqualTo(12L);
        assertThat(wegeOrder.actualInvestmentAmount()).isEqualByComparingTo("960.00");
        assertThat(wegeOrder.residualCashAmount()).isEqualByComparingTo("40.00");
        assertThat(wegeOrder.actualInvestmentAmount().add(wegeOrder.residualCashAmount()))
                .isEqualByComparingTo(wegeOrder.suggestedInvestment());
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
        assertThat(fiiOrder.suggestedQuantity()).isNull();
        assertThat(fiiOrder.actualInvestmentAmount()).isNull();
        assertThat(fiiOrder.residualCashAmount()).isNull();

        SuggestedOrderDTO stockOrder = suggestions.stream().filter(o -> o.category().equals("ACOES")).findFirst().get();
        assertThat(stockOrder.gapAmount()).isEqualByComparingTo("-2000.00"); // 5000 - 7000
        assertThat(stockOrder.suggestedInvestment()).isEqualByComparingTo("0.00");
        assertThat(stockOrder.suggestedQuantity()).isNull();
        assertThat(stockOrder.actualInvestmentAmount()).isNull();
        assertThat(stockOrder.residualCashAmount()).isNull();
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
    void shouldAcceptPolymorphicTargetsWhenTotalPercentageIsApproximately100() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        Portfolio portfolio = new Portfolio();
        Asset wege = asset(UUID.randomUUID(), "WEGE3", AssetCategory.ACOES);
        Asset petr = asset(UUID.randomUUID(), "PETR4", AssetCategory.ACOES);

        when(portfolioService.resolveOwnedPortfolio(user, portfolioId)).thenReturn(portfolio);
        when(assetRepository.findById(wege.getId())).thenReturn(Optional.of(wege));
        when(assetRepository.findById(petr.getId())).thenReturn(Optional.of(petr));

        List<SmartRebalancingService.PortfolioTargetInput> validTargets = List.of(
                SmartRebalancingService.PortfolioTargetInput.forCategory("FII", new BigDecimal("40.00")),
                SmartRebalancingService.PortfolioTargetInput.forAsset(wege.getId(), new BigDecimal("30.00")),
                SmartRebalancingService.PortfolioTargetInput.forAsset(petr.getId(), new BigDecimal("30.00"))
        );

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

    private Asset asset(UUID id, String ticker, AssetCategory category) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setTicker(ticker);
        asset.setCategory(category);
        return asset;
    }

    private PortfolioTarget assetTarget(Portfolio portfolio, Asset asset, BigDecimal targetPercentage) {
        PortfolioTarget target = new PortfolioTarget();
        target.setId(UUID.randomUUID());
        target.setPortfolio(portfolio);
        target.setAsset(asset);
        target.setTargetPercentage(targetPercentage);
        return target;
    }
}
