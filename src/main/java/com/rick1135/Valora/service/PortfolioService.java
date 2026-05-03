package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.PortfolioRequestDTO;
import com.rick1135.Valora.dto.response.PortfolioResponseDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.QuoteDTO;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PortfolioService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final PortfolioRepository portfolioRepository;
    private final PositionRepository positionRepository;
    private final ProventProvisionRepository proventProvisionRepository;
    private final TransactionRepository transactionRepository;
    private final QuoteService quoteService;
    private final PortfolioMapper portfolioMapper;
    private final PortfolioPerformanceCalculator performanceCalculator;

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
        List<Position> positions = positionRepository.findByPortfolioAndQuantityGreaterThan(portfolio, ZERO);

        List<String> tickers = positions.stream()
                .map(position -> position.getAsset().getTicker())
                .distinct()
                .toList();

        Map<String, QuoteDTO> currentQuotes = tickers.isEmpty() ? Map.of() : quoteService.getCurrentQuotes(tickers);
        BigDecimal totalProvents = defaultBigDecimal(
                proventProvisionRepository.sumNetAmountByPortfolioAndStatus(portfolio, ProventStatus.PAID)
        );

        return performanceCalculator.calculate(positions, currentQuotes, totalProvents);
    }

    @Transactional(readOnly = true)
    public Portfolio resolveOwnedPortfolio(User user, UUID portfolioId) {
        return portfolioRepository.findByIdAndUser(portfolioId, user)
                .orElseThrow(() -> new PortfolioNotFoundException("Carteira nao encontrada."));
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
