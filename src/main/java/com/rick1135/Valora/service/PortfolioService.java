package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.PortfolioRequestDTO;
import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.PortfolioResponseDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PortfolioService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int PERCENTAGE_SCALE = 4;

    private final PortfolioRepository portfolioRepository;
    private final PositionRepository positionRepository;
    private final ProventProvisionRepository proventProvisionRepository;
    private final TransactionRepository transactionRepository;
    private final QuoteService quoteService;
    private final PortfolioMapper portfolioMapper;

    @Transactional
    public PortfolioResponseDTO createPortfolio(User user, PortfolioRequestDTO dto) {
        String normalizedName = normalizeName(dto.name());
        if (portfolioRepository.existsByUserAndNameIgnoreCase(user, normalizedName)) {
            throw new IllegalArgumentException("Ja existe uma carteira com esse nome.");
        }

        Portfolio portfolio = portfolioMapper.toEntity(new PortfolioRequestDTO(normalizedName, dto.description()));
        portfolio.setUser(user);
        try {
            return portfolioMapper.toResponse(portfolioRepository.save(portfolio));
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("Ja existe uma carteira com esse nome.");
        }
    }

    @Transactional(readOnly = true)
    public List<PortfolioResponseDTO> listPortfolios(User user) {
        return portfolioRepository.findByUserOrderByCreatedAtAsc(user)
                .stream()
                .map(portfolioMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PortfolioResponseDTO getPortfolio(User user, UUID portfolioId) {
        return portfolioMapper.toResponse(resolveOwnedPortfolio(user, portfolioId));
    }

    @Transactional
    public PortfolioResponseDTO updatePortfolio(User user, UUID portfolioId, PortfolioRequestDTO dto) {
        Portfolio portfolio = resolveOwnedPortfolio(user, portfolioId);
        String normalizedName = normalizeName(dto.name());

        boolean nameChanged = !portfolio.getName().equalsIgnoreCase(normalizedName);
        if (nameChanged && portfolioRepository.existsByUserAndNameIgnoreCase(user, normalizedName)) {
            throw new IllegalArgumentException("Ja existe uma carteira com esse nome.");
        }

        portfolio.setName(normalizedName);
        portfolio.setDescription(dto.description());
        try {
            return portfolioMapper.toResponse(portfolioRepository.save(portfolio));
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("Ja existe uma carteira com esse nome.");
        }
    }

    @Transactional
    public void deletePortfolio(User user, UUID portfolioId) {
        Portfolio portfolio = resolveOwnedPortfolio(user, portfolioId);
        if (transactionRepository.existsByPortfolio(portfolio)
                || positionRepository.existsByPortfolio(portfolio)
                || proventProvisionRepository.existsByPortfolio(portfolio)) {
            throw new IllegalArgumentException("Carteiras com historico financeiro nao podem ser excluidas.");
        }
        portfolioRepository.delete(portfolio);
    }

    @Transactional(readOnly = true)
    public PortfolioSummaryDTO getPortfolioSummary(User user, UUID portfolioId) {
        Portfolio portfolio = resolveOwnedPortfolio(user, portfolioId);
        List<Position> positions = positionRepository.findByPortfolio(portfolio).stream()
                .filter(position -> position.getQuantity() != null && position.getQuantity().compareTo(ZERO) > 0)
                .toList();

        List<String> tickers = positions.stream()
                .map(position -> position.getAsset().getTicker())
                .distinct()
                .toList();

        Map<String, BigDecimal> currentPrices = quoteService.getCurrentPrices(tickers);

        BigDecimal totalInvested = positions.stream()
                .map(this::calculatePositionInvested)
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
                proventProvisionRepository.sumNetAmountByPortfolioAndStatus(portfolio, ProventStatus.PAID)
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

    @Transactional(readOnly = true)
    public Portfolio resolveOwnedPortfolio(User user, UUID portfolioId) {
        return portfolioRepository.findByIdAndUser(portfolioId, user)
                .orElseThrow(() -> new PortfolioNotFoundException("Carteira nao encontrada."));
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

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("O nome da carteira e obrigatorio.");
        }
        return normalized;
    }
}
