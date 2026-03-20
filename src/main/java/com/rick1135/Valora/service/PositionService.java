package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository positionRepository;
    private final QuoteService quoteService;

    public List<PositionResponseDTO> getUserPortfolio(User user) {
        return positionRepository.findByUser(user).stream()
                .filter(position -> position.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .map(position -> {
                    BigDecimal quantity = position.getQuantity();
                    BigDecimal averagePrice = position.getAveragePrice();
                    BigDecimal totalCost = quantity.multiply(averagePrice);

                    BigDecimal currentPrice = quoteService.getCurrentPrice(position.getAsset().getTicker()).orElse(null);
                    BigDecimal currentTotalValue = currentPrice == null ? null : quantity.multiply(currentPrice);

                    BigDecimal profitability = null;
                    if (currentTotalValue != null && totalCost.compareTo(BigDecimal.ZERO) > 0) {
                        profitability = currentTotalValue.subtract(totalCost)
                                .divide(totalCost, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"));
                    }

                    return new PositionResponseDTO(
                            position.getAsset().getId(),
                            position.getAsset().getTicker(),
                            position.getAsset().getName(),
                            quantity,
                            averagePrice,
                            totalCost,
                            currentPrice,
                            currentTotalValue,
                            profitability
                    );
                })
                .toList();
    }
}
