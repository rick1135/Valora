package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkType;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.mapper.PortfolioMapper;
import com.rick1135.Valora.repository.*;
import com.rick1135.Valora.service.PortfolioSnapshotService.PortfolioTwrSummary;
import org.mapstruct.factory.Mappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Mock
    private BenchmarkRepository benchmarkRepository;

    @Mock
    private BenchmarkValueRepository benchmarkValueRepository;

    @Mock
    private BenchmarkPerformanceCalculator benchmarkPerformanceCalculator;

    @Mock
    private PortfolioSnapshotService portfolioSnapshotService;

    @Spy
    private PortfolioMapper portfolioMapper = Mappers.getMapper(PortfolioMapper.class);

    @Spy
    private PortfolioPerformanceCalculator performanceCalculator = new PortfolioPerformanceCalculator(new FixedIncomeYieldCalculator());

    @Spy
    private MarketCalendar marketCalendar = new MarketCalendar(java.time.Clock.system(java.time.ZoneId.of("America/Sao_Paulo")));

    @Spy
    private PositionTimelineCalculator positionTimelineCalculator = new PositionTimelineCalculator(
            new MarketCalendar(java.time.Clock.system(java.time.ZoneId.of("America/Sao_Paulo")))
    );

    @Spy
    private FixedIncomeValuationService fixedIncomeValuationService = new FixedIncomeValuationService(
            new FixedIncomeYieldCalculator(),
            new MarketCalendar(java.time.Clock.system(java.time.ZoneId.of("America/Sao_Paulo"))),
            null
    );

    @InjectMocks
    private PortfolioService portfolioService;

    @org.junit.jupiter.api.BeforeEach
    void setupBenchmarks() {
        ReflectionTestUtils.setField(portfolioService, "portfolioSnapshotService", portfolioSnapshotService);
        org.mockito.Mockito.lenient().when(benchmarkRepository.findAll()).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(transactionRepository.findFirstTransactionDateByPortfolio(any())).thenReturn(Optional.empty());
    }

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
        assertThat(summary.profitability().twrPercentage()).isNull();
        assertThat(summary.profitability().twrReliable()).isFalse();
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

    @Test
    void getPortfolioSummaryShouldIncludeRealizedProfitWhenPositionIsClosed() {
        User user = new User();
        Portfolio portfolio = portfolio(user);

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("PETR4");
        asset.setCategory(AssetCategory.ACOES);

        Transaction buy = new Transaction();
        buy.setId(UUID.randomUUID());
        buy.setPortfolio(portfolio);
        buy.setAsset(asset);
        buy.setType(TransactionType.BUY);
        buy.setQuantity(new BigDecimal("10.00000000"));
        buy.setUnitPrice(new BigDecimal("10.00000000"));
        buy.setTransactionDate(java.time.Instant.parse("2026-01-10T14:00:00Z"));

        Transaction sell = new Transaction();
        sell.setId(UUID.randomUUID());
        sell.setPortfolio(portfolio);
        sell.setAsset(asset);
        sell.setType(TransactionType.SELL);
        sell.setQuantity(new BigDecimal("10.00000000"));
        sell.setUnitPrice(new BigDecimal("12.00000000"));
        sell.setTransactionDate(java.time.Instant.parse("2026-01-20T14:00:00Z"));

        when(portfolioRepository.findByIdAndUser(portfolio.getId(), user)).thenReturn(Optional.of(portfolio));
        when(positionRepository.findByPortfolioAndQuantityGreaterThan(portfolio, BigDecimal.ZERO)).thenReturn(List.of());
        when(proventProvisionRepository.sumNetAmountByPortfolioAndStatus(portfolio, ProventStatus.PAID))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.findByPortfolioOrderByTransactionDateAscIdAsc(portfolio))
                .thenReturn(List.of(buy, sell));
        when(portfolioSnapshotService.getPortfolioTwrSummary(portfolio))
                .thenReturn(Optional.of(new PortfolioTwrSummary(new BigDecimal("8.5000"), true)));

        PortfolioSummaryDTO summary = portfolioService.getPortfolioSummary(user, portfolio.getId());

        assertThat(summary.totalInvested()).isEqualByComparingTo("0");
        assertThat(summary.totalPatrimony()).isEqualByComparingTo("0");
        assertThat(summary.profitability().unrealizedProfit()).isEqualByComparingTo("0");
        assertThat(summary.profitability().realizedProfit()).isEqualByComparingTo("20.00");
        assertThat(summary.profitability().absoluteProfit()).isEqualByComparingTo("20.00");
        assertThat(summary.profitability().totalPercentage()).isNull();
        assertThat(summary.profitability().twrPercentage()).isEqualByComparingTo("8.5000");
        assertThat(summary.profitability().twrReliable()).isTrue();
    }

    @Test
    void getPortfolioSummaryShouldUseFixedIncomeValuationServiceAndSignalReliability() {
        User user = new User();
        Portfolio portfolio = portfolio(user);

        Asset cdbCdi = new Asset();
        cdbCdi.setId(UUID.randomUUID());
        cdbCdi.setTicker("CDB-CDI");
        cdbCdi.setCategory(AssetCategory.RENDA_FIXA);
        cdbCdi.setIndexer(FixedIncomeIndexer.CDI);
        cdbCdi.setAnnualRate(new BigDecimal("105.00"));

        Position cdbPosition = new Position();
        cdbPosition.setAsset(cdbCdi);
        cdbPosition.setQuantity(new BigDecimal("2"));
        cdbPosition.setAveragePrice(new BigDecimal("1000.00"));
        cdbPosition.setPurchaseDate(java.time.LocalDate.now().minusDays(90));

        when(portfolioRepository.findByIdAndUser(portfolio.getId(), user)).thenReturn(Optional.of(portfolio));
        when(positionRepository.findByPortfolioAndQuantityGreaterThan(portfolio, BigDecimal.ZERO))
                .thenReturn(List.of(cdbPosition));
        when(portfolioSnapshotService.getPortfolioTwrSummary(portfolio))
                .thenReturn(Optional.of(new PortfolioTwrSummary(new BigDecimal("1.2500"), false)));
        when(proventProvisionRepository.sumNetAmountByPortfolioAndStatus(portfolio, ProventStatus.PAID))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.findByPortfolioOrderByTransactionDateAscIdAsc(portfolio))
                .thenReturn(List.of());

        PortfolioSummaryDTO summary = portfolioService.getPortfolioSummary(user, portfolio.getId());

        assertThat(summary.totalInvested()).isEqualByComparingTo("2000.00000000");
        assertThat(summary.totalPatrimony()).isEqualByComparingTo("2000.00000000");
        assertThat(summary.fallbackQuoteUsed()).isTrue();
        assertThat(summary.fallbackTickers()).containsExactly("CDB-CDI");
        assertThat(summary.profitability().twrPercentage()).isEqualByComparingTo("1.2500");
        assertThat(summary.profitability().twrReliable()).isFalse();
        assertThat(summary.allocations().getFirst().marketPriceReliable()).isFalse();
        assertThat(summary.positions().getFirst().marketPriceReliable()).isFalse();
        verify(quoteService, never()).getCurrentQuotes(any());
    }

    @Test
    void getPortfolioSummaryShouldKeepUnavailableBenchmarkPercentageAsNull() {
        User user = new User();
        Portfolio portfolio = portfolio(user);
        Benchmark ibov = new Benchmark();
        ibov.setName("IBOV");
        ibov.setTicker("^BVSP");
        ibov.setType(BenchmarkType.INDEX);

        java.time.Instant firstTx = java.time.Instant.parse("2026-01-10T14:00:00Z");
        when(portfolioRepository.findByIdAndUser(portfolio.getId(), user)).thenReturn(Optional.of(portfolio));
        when(positionRepository.findByPortfolioAndQuantityGreaterThan(portfolio, BigDecimal.ZERO)).thenReturn(List.of());
        when(proventProvisionRepository.sumNetAmountByPortfolioAndStatus(portfolio, ProventStatus.PAID))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.findFirstTransactionDateByPortfolio(portfolio)).thenReturn(Optional.of(firstTx));
        when(benchmarkRepository.findAll()).thenReturn(List.of(ibov));
        when(benchmarkValueRepository.findByBenchmarkInRange(any(), any(), any())).thenReturn(List.of());
        when(benchmarkPerformanceCalculator.calculateYield(any(), any(), any(), any())).thenReturn(null);

        PortfolioSummaryDTO summary = portfolioService.getPortfolioSummary(user, portfolio.getId());

        assertThat(summary.profitability().benchmarkComparisons()).hasSize(1);
        assertThat(summary.profitability().benchmarkComparisons().getFirst().name()).isEqualTo("IBOV");
        assertThat(summary.profitability().benchmarkComparisons().getFirst().percentage()).isNull();
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
