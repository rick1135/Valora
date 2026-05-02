package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.ProventProvisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PortfolioService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int PERCENTAGE_SCALE = 4;

    private final PositionRepository positionRepository;
    private final ProventProvisionRepository proventProvisionRepository;
    private final QuoteService quoteService;

    @Transactional(readOnly = true)
    public PortfolioSummaryDTO getPortfolioSummary(User user) {
        List<Position> positions = positionRepository.findByUser(user).stream()
                .filter(position -> position.getQuantity() != null && position.getQuantity().compareTo(ZERO) > 0)
                .toList();

        List<String> tickers = positions.stream()
                .map(position -> position.getAsset().getTicker())
                .distinct()
                .toList();

        Map<String, BigDecimal> currentPrices = quoteService.getCurrentPrices(tickers);

        BigDecimal totalInvested = positions.stream()
                .map(position -> calculatePositionInvested(position))
                .reduce(ZERO, BigDecimal::add);

        Map<String, BigDecimal> patrimonyByTicker = positions.stream()
                .collect(Collectors.toMap(
                        position -> position.getAsset().getTicker(),
                        position -> calculatePositionPatrimony(position, currentPrices),
                        BigDecimal::add
                ));

        BigDecimal totalPatrimony = patrimonyByTicker.values().stream()
                .reduce(ZERO, BigDecimal::add);

        BigDecimal totalProvents = defaultBigDecimal(
                proventProvisionRepository.sumNetAmountByUserAndStatus(user, ProventStatus.PAID)
        );

        BigDecimal absoluteProfitLoss = totalPatrimony
                .add(totalProvents)
                .subtract(totalInvested);

        BigDecimal percentageProfitLoss = totalInvested.compareTo(ZERO) > 0
                ? absoluteProfitLoss
                .multiply(new BigDecimal("100"))
                .divide(totalInvested, PERCENTAGE_SCALE, RoundingMode.HALF_UP)
                : ZERO;

        List<AssetAllocationDTO> allocations = buildAllocations(positions, currentPrices, totalPatrimony);

        return new PortfolioSummaryDTO(
                totalPatrimony,
                totalInvested,
                totalProvents,
                absoluteProfitLoss,
                percentageProfitLoss,
                allocations
        );
    }

    private List<AssetAllocationDTO> buildAllocations(
            List<Position> positions,
            Map<String, BigDecimal> currentPrices,
            BigDecimal totalPatrimony
    ) {
        Map<String, BigDecimal> allocationByCategory = positions.stream()
                .collect(Collectors.toMap(
                        position -> position.getAsset().getCategory().name(),
                        position -> calculatePositionPatrimony(position, currentPrices),
                        BigDecimal::add
                ));

        return allocationByCategory.entrySet().stream()
                .map(entry -> new AssetAllocationDTO(
                        entry.getKey(),
                        entry.getValue(),
                        totalPatrimony.compareTo(ZERO) > 0
                                ? entry.getValue()
                                .multiply(new BigDecimal("100"))
                                .divide(totalPatrimony, PERCENTAGE_SCALE, RoundingMode.HALF_UP)
                                : ZERO
                ))
                .sorted(Comparator.comparing(AssetAllocationDTO::totalValue).reversed())
                .toList();
    }

    private BigDecimal calculatePositionInvested(Position position) {
        return safeMultiply(position.getQuantity(), position.getAveragePrice());
    }

    private BigDecimal calculatePositionPatrimony(Position position, Map<String, BigDecimal> currentPrices) {
        BigDecimal fallbackPrice = defaultBigDecimal(position.getAveragePrice());
        BigDecimal currentPrice = defaultBigDecimal(currentPrices.get(position.getAsset().getTicker()));
        BigDecimal effectivePrice = currentPrice.compareTo(ZERO) > 0 ? currentPrice : fallbackPrice;
        return safeMultiply(position.getQuantity(), effectivePrice);
    }

    private BigDecimal safeMultiply(BigDecimal left, BigDecimal right) {
        return defaultBigDecimal(left).multiply(defaultBigDecimal(right));
    }

    private BigDecimal defaultBigDecimal(BigDecimal value) {
        return value != null ? value : ZERO;
    }
}
