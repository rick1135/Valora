package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.AssetPositionSummaryDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Position;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PortfolioPerformanceCalculator {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final FixedIncomeYieldCalculator fixedIncomeYieldCalculator;
    private final FixedIncomeValuationService fixedIncomeValuationService;

    @Autowired
    public PortfolioPerformanceCalculator(
            FixedIncomeYieldCalculator fixedIncomeYieldCalculator,
            FixedIncomeValuationService fixedIncomeValuationService
    ) {
        this.fixedIncomeYieldCalculator = fixedIncomeYieldCalculator;
        this.fixedIncomeValuationService = fixedIncomeValuationService;
    }

    public PortfolioPerformanceCalculator(FixedIncomeYieldCalculator fixedIncomeYieldCalculator) {
        this(
                fixedIncomeYieldCalculator,
                new FixedIncomeValuationService(
                        fixedIncomeYieldCalculator,
                        new MarketCalendar(Clock.systemDefaultZone()),
                        null
                )
        );
    }

    public PortfolioSummaryDTO calculate(
            List<Position> positions,
            Map<String, QuoteDTO> currentQuotes,
            BigDecimal totalProvents,
            List<PortfolioSummaryDTO.BenchmarkComparisonDTO> benchmarkComparisons
    ) {
        return calculate(positions, currentQuotes, totalProvents, ZERO, benchmarkComparisons);
    }

    public PortfolioSummaryDTO calculate(
            List<Position> positions,
            Map<String, QuoteDTO> currentQuotes,
            BigDecimal totalProvents,
            BigDecimal realizedProfit,
            List<PortfolioSummaryDTO.BenchmarkComparisonDTO> benchmarkComparisons
    ) {
        return calculate(positions, currentQuotes, totalProvents, realizedProfit, benchmarkComparisons, Map.of());
    }

    public PortfolioSummaryDTO calculate(
            List<Position> positions,
            Map<String, QuoteDTO> currentQuotes,
            BigDecimal totalProvents,
            BigDecimal realizedProfit,
            List<PortfolioSummaryDTO.BenchmarkComparisonDTO> benchmarkComparisons,
            Map<Position, FixedIncomeValuationService.FixedIncomeValuationResult> fixedIncomeValuations
    ) {
        return calculate(
                positions, currentQuotes, totalProvents, realizedProfit, benchmarkComparisons, fixedIncomeValuations, null
        );
    }

    public PortfolioSummaryDTO calculate(
            List<Position> positions,
            Map<String, QuoteDTO> currentQuotes,
            BigDecimal totalProvents,
            BigDecimal realizedProfit,
            List<PortfolioSummaryDTO.BenchmarkComparisonDTO> benchmarkComparisons,
            Map<Position, FixedIncomeValuationService.FixedIncomeValuationResult> fixedIncomeValuations,
            BigDecimal twrPercentage
    ) {
        return calculate(
                positions, currentQuotes, totalProvents, realizedProfit, benchmarkComparisons, fixedIncomeValuations,
                twrPercentage, false
        );
    }

    public PortfolioSummaryDTO calculate(
            List<Position> positions,
            Map<String, QuoteDTO> currentQuotes,
            BigDecimal totalProvents,
            BigDecimal realizedProfit,
            List<PortfolioSummaryDTO.BenchmarkComparisonDTO> benchmarkComparisons,
            Map<Position, FixedIncomeValuationService.FixedIncomeValuationResult> fixedIncomeValuations,
            BigDecimal twrPercentage,
            boolean twrReliable
    ) {
        List<PositionSummaryLine> positionLines = positions.stream()
                .map(position -> buildPositionSummaryLine(position, currentQuotes, fixedIncomeValuations))
                .toList();

        BigDecimal totalInvested = positionLines.stream()
                .map(PositionSummaryLine::invested)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalPatrimony = positionLines.stream()
                .map(PositionSummaryLine::patrimony)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal unrealizedProfit = totalPatrimony.subtract(totalInvested);
        BigDecimal normalizedRealizedProfit = defaultBigDecimal(realizedProfit);
        BigDecimal normalizedTotalProvents = defaultBigDecimal(totalProvents);

        BigDecimal absoluteProfit = unrealizedProfit
                .add(normalizedRealizedProfit)
                .add(normalizedTotalProvents);

        BigDecimal totalPercentage;
        if (totalInvested.compareTo(ZERO) > 0) {
            totalPercentage = absoluteProfit
                    .multiply(new BigDecimal("100"))
                    .divide(totalInvested, FinancialConstants.PERCENTAGE_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        } else if (absoluteProfit.compareTo(ZERO) == 0) {
            totalPercentage = ZERO;
        } else {
            totalPercentage = null;
        }

        BigDecimal dayAbsoluteVariation = positionLines.stream()
                .map(PositionSummaryLine::dayAbsoluteVariation)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal previousPatrimony = totalPatrimony.subtract(dayAbsoluteVariation);
        BigDecimal dayPercentageVariation = previousPatrimony.compareTo(ZERO) > 0
                ? dayAbsoluteVariation
                .multiply(new BigDecimal("100"))
                .divide(previousPatrimony, FinancialConstants.PERCENTAGE_SCALE, FinancialConstants.DEFAULT_ROUNDING)
                : ZERO;
        boolean dayChangeAvailable = positionLines.stream().anyMatch(PositionSummaryLine::dayChangeAvailable);

        List<AssetAllocationDTO> allocations = buildAllocations(positionLines, totalPatrimony);
        List<AssetPositionSummaryDTO> positionSummaries = buildPositionSummaries(positionLines);
        List<String> fallbackTickers = positionLines.stream()
                .filter(PositionSummaryLine::fallbackQuoteUsed)
                .map(PositionSummaryLine::ticker)
                .distinct()
                .toList();

        return new PortfolioSummaryDTO(
                totalPatrimony,
                totalInvested,
                normalizedTotalProvents,
                new PortfolioSummaryDTO.ProfitabilityDTO(
                        absoluteProfit,
                        unrealizedProfit,
                        normalizedRealizedProfit,
                        totalPercentage,
                        dayAbsoluteVariation,
                        dayPercentageVariation,
                        dayChangeAvailable,
                        benchmarkComparisons,
                        twrPercentage,
                        twrReliable
                ),
                allocations,
                positionSummaries,
                !fallbackTickers.isEmpty(),
                fallbackTickers
        );
    }

    private List<AssetPositionSummaryDTO> buildPositionSummaries(List<PositionSummaryLine> positionLines) {
        return positionLines.stream()
                .map(line -> new AssetPositionSummaryDTO(
                        line.assetId(),
                        line.ticker(),
                        line.category(),
                        line.patrimony(),
                        line.quantity(),
                        line.currentPrice(),
                        !line.fallbackQuoteUsed(),
                        line.valuedThrough(),
                        line.pendingLatestObservation()
                ))
                .toList();
    }

    private List<AssetAllocationDTO> buildAllocations(List<PositionSummaryLine> positionLines, BigDecimal totalPatrimony) {
        Map<String, CategoryAllocationAccumulator> allocationByCategory = positionLines.stream()
                .collect(Collectors.groupingBy(
                        PositionSummaryLine::category,
                        Collectors.collectingAndThen(Collectors.toList(), this::accumulateCategoryAllocation)
                ));

        return allocationByCategory.entrySet().stream()
                .map(entry -> new AssetAllocationDTO(
                        entry.getKey(),
                        entry.getValue().totalValue(),
                        totalPatrimony.compareTo(ZERO) > 0
                                ? entry.getValue().totalValue()
                                .multiply(new BigDecimal("100"))
                                .divide(totalPatrimony, FinancialConstants.PERCENTAGE_SCALE, FinancialConstants.DEFAULT_ROUNDING)
                                : ZERO,
                        entry.getValue().currentPrice(),
                        entry.getValue().marketPriceReliable()
                ))
                .sorted(Comparator.comparing(AssetAllocationDTO::totalValue).reversed())
                .toList();
    }

    private CategoryAllocationAccumulator accumulateCategoryAllocation(List<PositionSummaryLine> lines) {
        BigDecimal totalValue = lines.stream()
                .map(PositionSummaryLine::patrimony)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal currentPrice = lines.size() == 1
                ? defaultBigDecimal(lines.getFirst().currentPrice())
                .setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, RoundingMode.HALF_UP)
                : ZERO;
        boolean marketPriceReliable = lines.stream().noneMatch(PositionSummaryLine::fallbackQuoteUsed);

        return new CategoryAllocationAccumulator(totalValue, currentPrice, marketPriceReliable);
    }

    private PositionSummaryLine buildPositionSummaryLine(
            Position position,
            Map<String, QuoteDTO> currentQuotes,
            Map<Position, FixedIncomeValuationService.FixedIncomeValuationResult> fixedIncomeValuations
    ) {
        String ticker = position.getAsset().getTicker();
        BigDecimal quantity = defaultBigDecimal(position.getQuantity());
        BigDecimal fallbackPrice = defaultBigDecimal(position.getAveragePrice());

        BigDecimal effectivePrice;
        BigDecimal patrimony;
        QuoteDTO quote = null;
        boolean hasValidQuote;
        LocalDate valuedThrough = null;
        boolean pendingLatestObservation = false;

        if (position.getAsset().getCategory() == AssetCategory.RENDA_FIXA) {
            FixedIncomeValuationService.FixedIncomeValuationResult valuation =
                    (fixedIncomeValuations != null && fixedIncomeValuations.containsKey(position))
                            ? fixedIncomeValuations.get(position)
                            : effectiveFixedIncomeValuationService().valuatePosition(position, List.of());
            hasValidQuote = valuation != null && valuation.reliable();
            if (hasValidQuote) {
                valuedThrough = valuation.valuedThrough();
                pendingLatestObservation = valuation.pendingLatestObservation();
            }
            effectivePrice = valuation != null && valuation.currentUnitPrice() != null
                    ? valuation.currentUnitPrice()
                    : fallbackPrice;
            patrimony = valuation != null && valuation.currentTotalValue() != null
                    ? valuation.currentTotalValue()
                    : safeMultiply(quantity, effectivePrice);
        } else {
            quote = currentQuotes.get(ticker);
            BigDecimal quotePrice = quote == null ? ZERO : defaultBigDecimal(quote.price());
            hasValidQuote = quotePrice.compareTo(ZERO) > 0;
            effectivePrice = hasValidQuote ? quotePrice : fallbackPrice;
            patrimony = safeMultiply(quantity, effectivePrice);
        }

        DayVariation dayVariation = calculateDayVariation(quantity, effectivePrice, quote, hasValidQuote && quote != null);

        return new PositionSummaryLine(
                ticker,
                position.getAsset().getId(),
                position.getAsset().getCategory().name(),
                quantity,
                effectivePrice,
                safeMultiply(quantity, fallbackPrice),
                patrimony,
                dayVariation.absoluteVariation(),
                dayVariation.available(),
                !hasValidQuote,
                valuedThrough,
                pendingLatestObservation
        );
    }

    private DayVariation calculateDayVariation(
            BigDecimal quantity,
            BigDecimal currentPrice,
            QuoteDTO quote,
            boolean hasValidQuote
    ) {
        if (!hasValidQuote || quote == null) {
            return new DayVariation(ZERO, false);
        }
        if (quote.change() != null) {
            return new DayVariation(safeMultiply(quantity, quote.change()), true);
        }
        if (quote.changePercent() == null) {
            return new DayVariation(ZERO, false);
        }

        BigDecimal changeRate = quote.changePercent().divide(new BigDecimal("100"), FinancialConstants.INTERMEDIATE_CALCULATION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        BigDecimal denominator = BigDecimal.ONE.add(changeRate);
        if (denominator.compareTo(ZERO) == 0) {
            return new DayVariation(ZERO, false);
        }
        BigDecimal previousPrice = currentPrice.divide(denominator, FinancialConstants.INTERMEDIATE_CALCULATION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        return new DayVariation(quantity.multiply(currentPrice.subtract(previousPrice)), true);
    }

    private FixedIncomeValuationService effectiveFixedIncomeValuationService() {
        return fixedIncomeValuationService != null
                ? fixedIncomeValuationService
                : new FixedIncomeValuationService(
                        fixedIncomeYieldCalculator,
                        new MarketCalendar(Clock.systemDefaultZone()),
                        null
                );
    }

    private BigDecimal safeMultiply(BigDecimal left, BigDecimal right) {
        return defaultBigDecimal(left).multiply(defaultBigDecimal(right));
    }

    private BigDecimal defaultBigDecimal(BigDecimal value) {
        return value != null ? value : ZERO;
    }

    private record PositionSummaryLine(
            String ticker,
            java.util.UUID assetId,
            String category,
            BigDecimal quantity,
            BigDecimal currentPrice,
            BigDecimal invested,
            BigDecimal patrimony,
            BigDecimal dayAbsoluteVariation,
            boolean dayChangeAvailable,
            boolean fallbackQuoteUsed,
            LocalDate valuedThrough,
            boolean pendingLatestObservation
    ) {
    }

    private record CategoryAllocationAccumulator(
            BigDecimal totalValue,
            BigDecimal currentPrice,
            boolean marketPriceReliable
    ) {
    }

    private record DayVariation(BigDecimal absoluteVariation, boolean available) {
    }
}
