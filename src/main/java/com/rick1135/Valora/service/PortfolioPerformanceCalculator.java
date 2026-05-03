package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
import com.rick1135.Valora.entity.Position;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PortfolioPerformanceCalculator {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public PortfolioSummaryDTO calculate(
            List<Position> positions,
            Map<String, QuoteDTO> currentQuotes,
            BigDecimal totalProvents
    ) {
        List<PositionSummaryLine> positionLines = positions.stream()
                .map(position -> buildPositionSummaryLine(position, currentQuotes))
                .toList();

        BigDecimal totalInvested = positionLines.stream()
                .map(PositionSummaryLine::invested)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalPatrimony = positionLines.stream()
                .map(PositionSummaryLine::patrimony)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal absoluteProfit = totalPatrimony
                .add(defaultBigDecimal(totalProvents))
                .subtract(totalInvested);

        BigDecimal totalPercentage = totalInvested.compareTo(ZERO) > 0
                ? absoluteProfit
                .multiply(new BigDecimal("100"))
                .divide(totalInvested, FinancialConstants.PERCENTAGE_SCALE, FinancialConstants.DEFAULT_ROUNDING)
                : ZERO;

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
        List<String> fallbackTickers = positionLines.stream()
                .filter(PositionSummaryLine::fallbackQuoteUsed)
                .map(PositionSummaryLine::ticker)
                .distinct()
                .toList();

        return new PortfolioSummaryDTO(
                totalPatrimony,
                totalInvested,
                defaultBigDecimal(totalProvents),
                new PortfolioSummaryDTO.ProfitabilityDTO(
                        absoluteProfit,
                        totalPercentage,
                        dayAbsoluteVariation,
                        dayPercentageVariation,
                        dayChangeAvailable
                ),
                allocations,
                !fallbackTickers.isEmpty(),
                fallbackTickers
        );
    }

    private List<AssetAllocationDTO> buildAllocations(List<PositionSummaryLine> positionLines, BigDecimal totalPatrimony) {
        Map<String, BigDecimal> allocationByCategory = positionLines.stream()
                .collect(Collectors.toMap(
                        PositionSummaryLine::category,
                        PositionSummaryLine::patrimony,
                        BigDecimal::add
                ));

        return allocationByCategory.entrySet().stream()
                .map(entry -> new AssetAllocationDTO(
                        entry.getKey(),
                        entry.getValue(),
                        totalPatrimony.compareTo(ZERO) > 0
                                ? entry.getValue()
                                .multiply(new BigDecimal("100"))
                                .divide(totalPatrimony, FinancialConstants.PERCENTAGE_SCALE, FinancialConstants.DEFAULT_ROUNDING)
                                : ZERO
                ))
                .sorted(Comparator.comparing(AssetAllocationDTO::totalValue).reversed())
                .toList();
    }

    private PositionSummaryLine buildPositionSummaryLine(Position position, Map<String, QuoteDTO> currentQuotes) {
        String ticker = position.getAsset().getTicker();
        BigDecimal quantity = defaultBigDecimal(position.getQuantity());
        BigDecimal fallbackPrice = defaultBigDecimal(position.getAveragePrice());
        QuoteDTO quote = currentQuotes.get(ticker);
        BigDecimal quotePrice = quote == null ? ZERO : defaultBigDecimal(quote.price());
        boolean hasValidQuote = quotePrice.compareTo(ZERO) > 0;
        BigDecimal effectivePrice = hasValidQuote ? quotePrice : fallbackPrice;
        BigDecimal patrimony = safeMultiply(quantity, effectivePrice);

        DayVariation dayVariation = calculateDayVariation(quantity, effectivePrice, quote, hasValidQuote);

        return new PositionSummaryLine(
                ticker,
                position.getAsset().getCategory().name(),
                safeMultiply(quantity, fallbackPrice),
                patrimony,
                dayVariation.absoluteVariation(),
                dayVariation.available(),
                !hasValidQuote
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

    private BigDecimal safeMultiply(BigDecimal left, BigDecimal right) {
        return defaultBigDecimal(left).multiply(defaultBigDecimal(right));
    }

    private BigDecimal defaultBigDecimal(BigDecimal value) {
        return value != null ? value : ZERO;
    }

    private record PositionSummaryLine(
            String ticker,
            String category,
            BigDecimal invested,
            BigDecimal patrimony,
            BigDecimal dayAbsoluteVariation,
            boolean dayChangeAvailable,
            boolean fallbackQuoteUsed
    ) {
    }

    private record DayVariation(BigDecimal absoluteVariation, boolean available) {
    }
}
