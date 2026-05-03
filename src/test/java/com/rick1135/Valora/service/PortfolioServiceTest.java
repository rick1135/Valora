package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.mapper.PortfolioMapper;
import com.rick1135.Valora.repository.PortfolioRepository;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.ProventProvisionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import org.mapstruct.factory.Mappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private ProventProvisionRepository proventProvisionRepository;

    @Mock
    private PortfolioRepository portfolioRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private QuoteService quoteService;

    @Spy
    private PortfolioMapper portfolioMapper = Mappers.getMapper(PortfolioMapper.class);

    @Spy
    private PortfolioPerformanceCalculator performanceCalculator = new PortfolioPerformanceCalculator();

    @InjectMocks
    private PortfolioService portfolioService;

    @Test
    void getPortfolioSummaryShouldCalculateTotalsAndAllocations() {
        User user = new User();
        Portfolio portfolio = portfolio(user);

        Position stockPosition = buildPosition("VALE3", AssetCategory.ACOES, "5.00000000", "40.00");
        Position etfPosition = buildPosition("BOVA11", AssetCategory.ETF, "2.00000000", "50.00");

        when(portfolioRepository.findByIdAndUser(portfolio.getId(), user)).thenReturn(Optional.of(portfolio));
        when(positionRepository.findByPortfolioAndQuantityGreaterThan(portfolio, BigDecimal.ZERO))
                .thenReturn(List.of(stockPosition, etfPosition));
        when(quoteService.getCurrentQuotes(List.of("VALE3", "BOVA11")))
                .thenReturn(Map.of("VALE3", new QuoteDTO(
                        "VALE3",
                        new BigDecimal("50.00"),
                        new BigDecimal("1.00"),
                        new BigDecimal("2.0408"),
                        null,
                        null,
                        null
                )));
        when(proventProvisionRepository.sumNetAmountByPortfolioAndStatus(portfolio, ProventStatus.PAID))
                .thenReturn(new BigDecimal("50.00"));

        PortfolioSummaryDTO summary = portfolioService.getPortfolioSummary(user, portfolio.getId());

        assertThat(summary.totalInvested()).isEqualByComparingTo("300.0000000000");
        assertThat(summary.totalPatrimony()).isEqualByComparingTo("350.0000000000");
        assertThat(summary.totalProvents()).isEqualByComparingTo("50.00");
        assertThat(summary.profitability().absoluteProfit()).isEqualByComparingTo("100.0000000000");
        assertThat(summary.profitability().totalPercentage()).isEqualByComparingTo("33.3333");
        assertThat(summary.profitability().dayAbsoluteVariation()).isEqualByComparingTo("5.00000000");
        assertThat(summary.profitability().dayPercentageVariation()).isEqualByComparingTo("1.4493");
        assertThat(summary.profitability().dayChangeAvailable()).isTrue();
        assertThat(summary.fallbackQuoteUsed()).isTrue();
        assertThat(summary.fallbackTickers()).containsExactly("BOVA11");
        assertThat(summary.allocations()).extracting(AssetAllocationDTO::category)
                .containsExactly("ACOES", "ETF");
        assertThat(summary.allocations().getFirst().totalValue()).isEqualByComparingTo("250.0000000000");

        BigDecimal allocationSum = summary.allocations().stream()
                .map(AssetAllocationDTO::percentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(allocationSum).isEqualByComparingTo("100.0000");
        verify(quoteService).getCurrentQuotes(List.of("VALE3", "BOVA11"));
    }

    @Test
    void getPortfolioSummaryShouldHandleEmptyPortfolioWithoutDivisionByZero() {
        User user = new User();
        Portfolio portfolio = portfolio(user);
        when(portfolioRepository.findByIdAndUser(portfolio.getId(), user)).thenReturn(Optional.of(portfolio));
        when(positionRepository.findByPortfolioAndQuantityGreaterThan(portfolio, BigDecimal.ZERO)).thenReturn(List.of());
        when(proventProvisionRepository.sumNetAmountByPortfolioAndStatus(portfolio, ProventStatus.PAID))
                .thenReturn(null);

        PortfolioSummaryDTO summary = portfolioService.getPortfolioSummary(user, portfolio.getId());

        assertThat(summary.totalInvested()).isEqualByComparingTo("0");
        assertThat(summary.totalPatrimony()).isEqualByComparingTo("0");
        assertThat(summary.totalProvents()).isEqualByComparingTo("0");
        assertThat(summary.profitability().absoluteProfit()).isEqualByComparingTo("0");
        assertThat(summary.profitability().totalPercentage()).isEqualByComparingTo("0");
        assertThat(summary.profitability().dayAbsoluteVariation()).isEqualByComparingTo("0");
        assertThat(summary.profitability().dayPercentageVariation()).isEqualByComparingTo("0");
        assertThat(summary.profitability().dayChangeAvailable()).isFalse();
        assertThat(summary.fallbackQuoteUsed()).isFalse();
        assertThat(summary.fallbackTickers()).isEmpty();
        assertThat(summary.allocations()).isEmpty();
        verify(quoteService, never()).getCurrentQuotes(any());
    }

    @Test
    void getPortfolioSummaryShouldUseAveragePriceFallbackWhenQuoteIsNull() {
        User user = new User();
        Portfolio portfolio = portfolio(user);
        Position position = buildPosition("ITSA4", AssetCategory.ACOES, "10.00000000", "12.00");

        when(portfolioRepository.findByIdAndUser(portfolio.getId(), user)).thenReturn(Optional.of(portfolio));
        when(positionRepository.findByPortfolioAndQuantityGreaterThan(portfolio, BigDecimal.ZERO))
                .thenReturn(List.of(position));
        when(quoteService.getCurrentQuotes(List.of("ITSA4"))).thenReturn(Map.of());
        when(proventProvisionRepository.sumNetAmountByPortfolioAndStatus(portfolio, ProventStatus.PAID))
                .thenReturn(BigDecimal.ZERO);

        PortfolioSummaryDTO summary = portfolioService.getPortfolioSummary(user, portfolio.getId());

        assertThat(summary.totalInvested()).isEqualByComparingTo("120.0000000000");
        assertThat(summary.totalPatrimony()).isEqualByComparingTo("120.0000000000");
        assertThat(summary.profitability().absoluteProfit()).isEqualByComparingTo("0");
        assertThat(summary.profitability().dayChangeAvailable()).isFalse();
        assertThat(summary.fallbackQuoteUsed()).isTrue();
        assertThat(summary.fallbackTickers()).containsExactly("ITSA4");
    }

    @Test
    void resolveOwnedPortfolioShouldRejectUnknownOrForeignPortfolio() {
        User user = new User();
        UUID foreignPortfolioId = UUID.randomUUID();

        when(portfolioRepository.findByIdAndUser(foreignPortfolioId, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> portfolioService.resolveOwnedPortfolio(user, foreignPortfolioId))
                .isInstanceOf(PortfolioNotFoundException.class)
                .hasMessage("Carteira nao encontrada.");
    }

    @Test
    void getPortfolioSummaryShouldRejectPortfolioNotOwnedBeforeFinancialQueries() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        when(portfolioRepository.findByIdAndUser(portfolioId, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> portfolioService.getPortfolioSummary(user, portfolioId))
                .isInstanceOf(PortfolioNotFoundException.class);
        verify(positionRepository, never()).findByPortfolioAndQuantityGreaterThan(any(), any());
        verify(proventProvisionRepository, never()).sumNetAmountByPortfolioAndStatus(any(), any());
        verify(quoteService, never()).getCurrentQuotes(any());
    }

    @Test
    void deletePortfolioShouldRejectPortfolioNotOwnedBeforeDeleteGuards() {
        User user = new User();
        UUID portfolioId = UUID.randomUUID();
        when(portfolioRepository.findByIdAndUser(portfolioId, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> portfolioService.deletePortfolio(user, portfolioId))
                .isInstanceOf(PortfolioNotFoundException.class);
        verify(transactionRepository, never()).existsByPortfolio(any());
        verify(positionRepository, never()).existsByPortfolio(any());
        verify(proventProvisionRepository, never()).existsByPortfolio(any());
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

    private Portfolio portfolio(User user) {
        Portfolio portfolio = new Portfolio();
        portfolio.setId(UUID.randomUUID());
        portfolio.setUser(user);
        portfolio.setName("Principal");
        return portfolio;
    }
}
