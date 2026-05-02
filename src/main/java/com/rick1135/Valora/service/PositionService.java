package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.mapper.PositionMapper;
import com.rick1135.Valora.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository positionRepository;
    private final QuoteService quoteService;
    private final PositionMapper positionMapper;

    @Transactional(readOnly = true)
    public List<PositionResponseDTO> getUserPortfolio(User user) {
        List<Position> positions = positionRepository.findByUser(user).stream()
                .filter(position -> position.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        List<String> tickers = positions.stream()
                .map(position -> position.getAsset().getTicker())
                .distinct()
                .toList();

        var pricesMap = quoteService.getCurrentPrices(tickers);

        return positions.stream()
                .map(position -> {
                    BigDecimal quantity = position.getQuantity();
                    BigDecimal averagePrice = position.getAveragePrice();
                    BigDecimal totalCost = quantity.multiply(averagePrice);

                    BigDecimal currentPrice = pricesMap.getOrDefault(position.getAsset().getTicker(), BigDecimal.ZERO);
                    BigDecimal currentTotalValue = currentPrice.compareTo(BigDecimal.ZERO) > 0
                            ? quantity.multiply(currentPrice)
                            : null;

                    BigDecimal profitability = null;
                    if (currentTotalValue != null && totalCost.compareTo(BigDecimal.ZERO) > 0) {
                        profitability = currentTotalValue.subtract(totalCost)
                                .divide(totalCost, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                    }

                    return positionMapper.toResponse(
                            position,
                            totalCost,
                            currentPrice,
                            currentTotalValue,
                            profitability
                    );
                })
                .toList();
    }
}
