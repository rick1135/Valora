package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.mapper.PositionMapper;
import com.rick1135.Valora.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository positionRepository;
    private final QuoteService quoteService;
    private final PositionMapper positionMapper;
    private final PortfolioService portfolioService;
    private final FixedIncomeValuationService fixedIncomeValuationService;

    @Transactional(readOnly = true)
    public List<PositionResponseDTO> getUserPortfolio(User user, java.util.UUID portfolioId) {
        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, portfolioId);
        List<Position> positions = positionRepository.findByPortfolio(portfolio).stream()
                .filter(position -> position.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        List<Position> fixedIncomePositions = positions.stream()
                .filter(position -> position.getAsset().getCategory() == AssetCategory.RENDA_FIXA)
                .toList();

        List<String> variableIncomeTickers = positions.stream()
                .filter(position -> position.getAsset().getCategory() != AssetCategory.RENDA_FIXA)
                .map(position -> position.getAsset().getTicker())
                .distinct()
                .toList();

        Map<String, BigDecimal> pricesMap = variableIncomeTickers.isEmpty()
                ? Map.of()
                : quoteService.getCurrentPrices(variableIncomeTickers);

        Map<Position, FixedIncomeValuationService.FixedIncomeValuationResult> fixedIncomeValuations =
                fixedIncomePositions.isEmpty()
                        ? Map.of()
                        : fixedIncomeValuationService.valuatePortfolioPositions(portfolio, fixedIncomePositions);

        return positions.stream()
                .map(position -> buildPositionResponse(position, pricesMap, fixedIncomeValuations))
                .toList();
    }

    private PositionResponseDTO buildPositionResponse(
            Position position,
            Map<String, BigDecimal> pricesMap,
            Map<Position, FixedIncomeValuationService.FixedIncomeValuationResult> fixedIncomeValuations
    ) {
        BigDecimal quantity = position.getQuantity();
        BigDecimal averagePrice = position.getAveragePrice();
        BigDecimal totalCost = quantity.multiply(averagePrice);

        BigDecimal currentPrice;
        BigDecimal currentTotalValue;
        LocalDate valuedThrough = null;
        boolean pendingLatestObservation = false;

        if (position.getAsset().getCategory() == AssetCategory.RENDA_FIXA) {
            FixedIncomeValuationService.FixedIncomeValuationResult valuation = fixedIncomeValuations.get(position);
            if (valuation != null && valuation.reliable()) {
                currentPrice = valuation.currentUnitPrice();
                currentTotalValue = valuation.currentTotalValue();
                valuedThrough = valuation.valuedThrough();
                pendingLatestObservation = valuation.pendingLatestObservation();
            } else {
                currentPrice = null;
                currentTotalValue = null;
            }
        } else {
            currentPrice = pricesMap.getOrDefault(position.getAsset().getTicker(), BigDecimal.ZERO);
            currentTotalValue = currentPrice.compareTo(BigDecimal.ZERO) > 0
                    ? quantity.multiply(currentPrice)
                    : null;
        }

        BigDecimal profitability = null;
        if (currentTotalValue != null && totalCost.compareTo(BigDecimal.ZERO) > 0) {
            profitability = currentTotalValue.subtract(totalCost)
                    .divide(totalCost, FinancialConstants.PERCENTAGE_SCALE, FinancialConstants.DEFAULT_ROUNDING)
                    .multiply(new BigDecimal("100"));
        }

        return positionMapper.toResponse(
                position,
                totalCost,
                currentPrice,
                currentTotalValue,
                profitability,
                valuedThrough,
                pendingLatestObservation
        );
    }
}
