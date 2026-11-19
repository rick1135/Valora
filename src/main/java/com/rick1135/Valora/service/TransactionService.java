package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.TransactionDTO;
import com.rick1135.Valora.dto.response.TransactionResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.mapper.TransactionMapper;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final PositionRepository positionRepository;
    private final AssetRepository assetRepository;
    private final TransactionMapper transactionMapper;
    private final PortfolioService portfolioService;
    private final MarketCalendar marketCalendar;
    private final PositionTimelineCalculator positionTimelineCalculator;
    private final ProventService proventService;

    @Transactional
    public TransactionResponseDTO processTransaction(User user, TransactionDTO dto) {
        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, dto.portfolioId());
        Asset asset = assetRepository.findById(dto.assetId())
                .orElseThrow(() -> new AssetNotFoundException("Ativo nao encontrado"));

        Transaction transaction = transactionMapper.toEntity(dto);
        transaction.setPortfolio(portfolio);
        transaction.setAsset(asset);

        List<Transaction> timeline = new ArrayList<>(
                transactionRepository.findByPortfolioAndAssetOrderByTransactionDateAscIdAsc(portfolio, asset)
        );
        timeline.add(transaction);

        PositionTimelineCalculator.PositionTimelineResult timelineResult = effectiveTimelineCalculator().replay(timeline);
        Transaction savedTransaction = transactionRepository.save(transaction);

        Position position = positionRepository.findByPortfolioAndAsset(portfolio, asset)
                .orElseGet(() -> {
                    Position newPosition = new Position();
                    newPosition.setPortfolio(portfolio);
                    newPosition.setAsset(asset);
                    return newPosition;
                });

        position.setQuantity(timelineResult.quantity());
        position.setAveragePrice(timelineResult.averagePrice());
        position.setPurchaseDate(timelineResult.purchaseDate());

        Position savedPosition = positionRepository.save(position);
        reconcileProventsFromTransactionDate(portfolio, asset, savedTransaction);
        return transactionMapper.toResponse(savedTransaction, asset, savedPosition);
    }

    private void reconcileProventsFromTransactionDate(
            Portfolio portfolio,
            Asset asset,
            Transaction savedTransaction
    ) {
        if (proventService == null || savedTransaction.getTransactionDate() == null) {
            return;
        }
        LocalDate transactionMarketDate = effectiveMarketCalendar().marketDate(savedTransaction.getTransactionDate());
        proventService.reconcileProvisionsForPortfolioAndAsset(portfolio, asset, transactionMarketDate);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponseDTO> getTransactionHistory(
            User user,
            java.util.UUID portfolioId,
            String ticker,
            TransactionType type,
            Instant startDate,
            Instant endDate,
            Pageable pageable
    ) {
        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, portfolioId);
        String normalizedTicker = normalizeTicker(ticker);
        return transactionRepository.findTransactionHistoryByPortfolioAndFilters(
                        portfolio,
                        normalizedTicker,
                        type,
                        startDate,
                        endDate,
                        pageable
                )
                .map(transactionMapper::toHistoryResponse);
    }

    private PositionTimelineCalculator effectiveTimelineCalculator() {
        return positionTimelineCalculator != null
                ? positionTimelineCalculator
                : new PositionTimelineCalculator(effectiveMarketCalendar());
    }

    private MarketCalendar effectiveMarketCalendar() {
        return marketCalendar != null ? marketCalendar : new MarketCalendar(java.time.Clock.systemDefaultZone());
    }

    private String normalizeTicker(String ticker) {
        if (ticker == null) {
            return null;
        }

        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}
