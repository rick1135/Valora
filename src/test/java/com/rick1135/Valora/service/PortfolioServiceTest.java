package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.ProventProvisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private ProventProvisionRepository proventProvisionRepository;

    @Mock
    private QuoteService quoteService;

    @InjectMocks
    private PortfolioService portfolioService;

    @Test
    void getPortfolioSummaryShouldCalculateTotalsAndAllocations() {
        User user = new User();

        Position stockPosition = buildPosition("VALE3", AssetCategory.ACOES, "5.00000000", "40.00");
        Position etfPosition = buildPosition("BOVA11", AssetCategory.ETF, "2.00000000", "50.00");
        Position zeroPosition = buildPosition("PETR4", AssetCategory.ACOES, "0.00000000", "30.00");

        when(positionRepository.findByUser(user)).thenReturn(List.of(stockPosition, etfPosition, zeroPosition));
        when(quoteService.getCurrentPrices(List.of("VALE3", "BOVA11")))
                .thenReturn(Map.of("VALE3", new BigDecimal("50.00")));
        when(proventProvisionRepository.sumNetAmountByUserAndStatus(user, ProventStatus.PAID))
                .thenReturn(new BigDecimal("50.00"));

        PortfolioSummaryDTO summary = portfolioService.getPortfolioSummary(user);

        assertThat(summary.totalInvested()).isEqualByComparingTo("300.0000000000");
        assertThat(summary.totalPatrimony()).isEqualByComparingTo("350.0000000000");
        assertThat(summary.totalProvents()).isEqualByComparingTo("50.00");
        assertThat(summary.absoluteProfitLoss()).isEqualByComparingTo("100.0000000000");
        assertThat(summary.percentageProfitLoss()).isEqualByComparingTo("33.3333");
        assertThat(summary.allocations()).extracting(AssetAllocationDTO::category)
                .containsExactly("ACOES", "ETF");
        assertThat(summary.allocations().getFirst().totalValue()).isEqualByComparingTo("250.0000000000");

        BigDecimal allocationSum = summary.allocations().stream()
                .map(AssetAllocationDTO::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(allocationSum).isEqualByComparingTo("100.0000");
    }

    @Test
    void getPortfolioSummaryShouldHandleEmptyPortfolioWithoutDivisionByZero() {
        User user = new User();
        when(positionRepository.findByUser(user)).thenReturn(List.of());
        when(quoteService.getCurrentPrices(List.of())).thenReturn(Map.of());
        when(proventProvisionRepository.sumNetAmountByUserAndStatus(user, ProventStatus.PAID))
                .thenReturn(null);

        PortfolioSummaryDTO summary = portfolioService.getPortfolioSummary(user);

        assertThat(summary.totalInvested()).isEqualByComparingTo("0");
        assertThat(summary.totalPatrimony()).isEqualByComparingTo("0");
        assertThat(summary.totalProvents()).isEqualByComparingTo("0");
        assertThat(summary.absoluteProfitLoss()).isEqualByComparingTo("0");
        assertThat(summary.percentageProfitLoss()).isEqualByComparingTo("0");
        assertThat(summary.allocations()).isEmpty();
    }

    private Position buildPosition(String ticker, AssetCategory category, String quantity, String averagePrice) {
        Asset asset = new Asset();
        asset.setTicker(ticker);
        asset.setCategory(category);

        Position position = new Position();
        position.setAsset(asset);
        position.setQuantity(new BigDecimal(quantity));
        position.setAveragePrice(new BigDecimal(averagePrice));
        return position;
    }
}
