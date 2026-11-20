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
import com.rick1135.Valora.exception.TransactionNotFoundException;
import com.rick1135.Valora.mapper.TransactionMapper;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {
    private static final String IDEMPOTENCY_INDEX = "ux_transactions_portfolio_idempotency_key";

    private final TransactionRepository transactionRepository;
    private final PositionRepository positionRepository;
    private final AssetRepository assetRepository;
    private final TransactionMapper transactionMapper;
    private final PortfolioService portfolioService;
    private final MarketCalendar marketCalendar;
    private final PositionTimelineCalculator positionTimelineCalculator;
    private final ProventService proventService;
    private final PlatformTransactionManager transactionManager;

    @Autowired(required = false)
    private PortfolioSnapshotService portfolioSnapshotService;

    public TransactionResponseDTO processTransaction(User user, TransactionDTO dto) {
        return processTransaction(user, dto, null).response();
    }

    public TransactionResult processTransaction(User user, TransactionDTO dto, String idempotencyKey) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        if (key != null) {
            // A unique-index violation aborts the PostgreSQL transaction. Retry in a fresh one.
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        try {
            return template.execute(status -> createOrReplayTransaction(user, dto, key));
        } catch (DataIntegrityViolationException exception) {
            if (key == null || !isIdempotencyViolation(exception)) {
                throw exception;
            }
            return template.execute(status -> {
                Portfolio portfolio = lockOwnedPortfolio(user, dto.portfolioId());
                return transactionRepository.findByPortfolioAndIdempotencyKey(portfolio, key)
                        .map(existing -> replayTransaction(existing, dto))
                        .orElseThrow(() -> exception);
            });
        }
    }

    private TransactionResult createOrReplayTransaction(User user, TransactionDTO dto, String key) {
        Portfolio portfolio = lockOwnedPortfolio(user, dto.portfolioId());
        if (key != null) {
            Optional<Transaction> existing = transactionRepository.findByPortfolioAndIdempotencyKey(portfolio, key);
            if (existing.isPresent()) {
                return replayTransaction(existing.get(), dto);
            }
        }

        Asset asset = findAsset(dto.assetId());
        Transaction transaction = transactionMapper.toEntity(dto);
        transaction.setPortfolio(portfolio);
        transaction.setAsset(asset);
        transaction.setIdempotencyKey(key);

        List<Transaction> timeline = loadTimeline(portfolio, asset);
        if (key != null) {
            transaction.setIdempotencyPayload(idempotencyPayload(dto));
            transaction = transactionRepository.saveAndFlush(transaction);
        }
        timeline.add(transaction);

        PositionTimelineCalculator.PositionTimelineResult timelineResult = effectiveTimelineCalculator().replay(timeline);
        if (key == null) {
            transaction = transactionRepository.save(transaction);
        }
        Position position = savePosition(portfolio, asset, timelineResult);
        reconcileProventsFromTransactionDate(portfolio, asset, transaction.getTransactionDate());
        invalidateSnapshots(portfolio, transaction.getTransactionDate());
        return new TransactionResult(transactionMapper.toResponse(transaction, asset, position), false);
    }

    private TransactionResult replayTransaction(Transaction existing, TransactionDTO dto) {
        Asset asset = existing.getAsset();
        String storedPayload = existing.getIdempotencyPayload();
        if (storedPayload == null) {
            storedPayload = idempotencyPayload(new TransactionDTO(existing.getPortfolio().getId(), asset.getId(),
                    existing.getType(), existing.getQuantity(), existing.getUnitPrice(), existing.getTransactionDate()));
        }
        if (!storedPayload.equals(idempotencyPayload(dto))) {
            throw new IllegalArgumentException("Idempotency-Key ja utilizada com dados diferentes.");
        }
        Position position = positionRepository.findByPortfolioAndAsset(existing.getPortfolio(), asset).orElse(null);
        return new TransactionResult(transactionMapper.toResponse(existing, asset, position), true);
    }

    @Transactional
    public TransactionResponseDTO updateTransaction(User user, UUID transactionId, TransactionDTO dto) {
        Transaction transaction = lockOwnedTransaction(user, transactionId);
        Portfolio oldPortfolio = transaction.getPortfolio();
        Asset oldAsset = transaction.getAsset();
        Instant oldDate = transaction.getTransactionDate();

        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, dto.portfolioId());
        Asset asset = findAsset(dto.assetId());
        boolean portfolioChanged = !oldPortfolio.getId().equals(portfolio.getId());
        boolean pairChanged = portfolioChanged || !oldAsset.getId().equals(asset.getId());
        String key = transaction.getIdempotencyKey();
        if (portfolioChanged && key != null
                && transactionRepository.findByPortfolioAndIdempotencyKey(portfolio, key).isPresent()) {
            throw new IllegalArgumentException("Idempotency-Key ja utilizada na carteira de destino.");
        }

        List<Transaction> timeline = loadTimeline(portfolio, asset);
        PositionTimelineCalculator.PositionTimelineResult oldResult = null;
        if (pairChanged) {
            List<Transaction> oldTimeline = loadTimeline(oldPortfolio, oldAsset);
            oldTimeline.removeIf(entry -> transactionId.equals(entry.getId()));
            oldResult = effectiveTimelineCalculator().replay(oldTimeline);
        } else {
            timeline.removeIf(entry -> transactionId.equals(entry.getId()));
        }

        transaction.setPortfolio(portfolio);
        transaction.setAsset(asset);
        transaction.setType(dto.type());
        transaction.setQuantity(dto.quantity());
        transaction.setUnitPrice(dto.unitPrice());
        transaction.setTransactionDate(dto.transactionDate());
        if (key != null) {
            transaction.setIdempotencyPayload(idempotencyPayload(dto));
        }
        timeline.add(transaction);

        PositionTimelineCalculator.PositionTimelineResult timelineResult = effectiveTimelineCalculator().replay(timeline);
        Position position = savePosition(portfolio, asset, timelineResult);
        Instant affectedDate = oldDate.isBefore(dto.transactionDate()) ? oldDate : dto.transactionDate();
        if (oldResult != null) {
            savePosition(oldPortfolio, oldAsset, oldResult);
            reconcileProventsFromTransactionDate(oldPortfolio, oldAsset, affectedDate);
        }
        reconcileProventsFromTransactionDate(portfolio, asset, affectedDate);
        invalidateSnapshots(portfolio, affectedDate);
        if (portfolioChanged) {
            invalidateSnapshots(oldPortfolio, affectedDate);
        }
        return transactionMapper.toResponse(transaction, asset, position);
    }

    @Transactional
    public void deleteTransaction(User user, UUID transactionId) {
        Transaction transaction = lockOwnedTransaction(user, transactionId);
        Portfolio portfolio = transaction.getPortfolio();
        Asset asset = transaction.getAsset();
        List<Transaction> timeline = loadTimeline(portfolio, asset);
        timeline.removeIf(entry -> transactionId.equals(entry.getId()));

        PositionTimelineCalculator.PositionTimelineResult timelineResult = effectiveTimelineCalculator().replay(timeline);
        transactionRepository.delete(transaction);
        savePosition(portfolio, asset, timelineResult);
        reconcileProventsFromTransactionDate(portfolio, asset, transaction.getTransactionDate());
        invalidateSnapshots(portfolio, transaction.getTransactionDate());
    }

    private Position savePosition(
            Portfolio portfolio,
            Asset asset,
            PositionTimelineCalculator.PositionTimelineResult timelineResult
    ) {
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

        return positionRepository.save(position);
    }

    private List<Transaction> loadTimeline(Portfolio portfolio, Asset asset) {
        return new ArrayList<>(
                transactionRepository.findByPortfolioAndAssetOrderByTransactionDateAscIdAsc(portfolio, asset)
        );
    }

    private Portfolio lockOwnedPortfolio(User user, UUID portfolioId) {
        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, portfolioId);
        // Serialize timeline changes, including moves between two portfolios owned by the same user.
        transactionRepository.lockOwnerForTransactionChanges(user.getId());
        return portfolio;
    }

    private Transaction lockOwnedTransaction(User user, UUID transactionId) {
        transactionRepository.lockOwnerForTransactionChanges(user.getId());
        return transactionRepository.findById(transactionId)
                .filter(transaction -> Objects.equals(transaction.getPortfolio().getUser().getId(), user.getId()))
                .orElseThrow(() -> new TransactionNotFoundException("Transacao nao encontrada."));
    }

    private Asset findAsset(UUID assetId) {
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new AssetNotFoundException("Ativo nao encontrado"));
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.codePointCount(0, normalized.length()) > 128) {
            throw new IllegalArgumentException("Idempotency-Key deve ter no maximo 128 caracteres.");
        }
        return normalized;
    }

    private String idempotencyPayload(TransactionDTO dto) {
        // Keep the input precision before PostgreSQL rounds decimals and timestamps.
        return String.join("|",
                dto.assetId().toString(),
                dto.type().name(),
                dto.quantity().stripTrailingZeros().toString(),
                dto.unitPrice().stripTrailingZeros().toString(),
                dto.transactionDate().toString());
    }

    private boolean isIdempotencyViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && IDEMPOTENCY_INDEX.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private void invalidateSnapshots(Portfolio portfolio, Instant transactionDate) {
        if (portfolioSnapshotService != null) {
            LocalDate marketDate = effectiveMarketCalendar().marketDate(transactionDate);
            portfolioSnapshotService.invalidateSnapshotsFrom(portfolio, marketDate);
        }
    }

    private void reconcileProventsFromTransactionDate(
            Portfolio portfolio,
            Asset asset,
            Instant transactionDate
    ) {
        if (proventService == null || transactionDate == null) {
            return;
        }
        LocalDate transactionMarketDate = effectiveMarketCalendar().marketDate(transactionDate);
        proventService.reconcileProvisionsForPortfolioAndAsset(portfolio, asset, transactionMarketDate);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponseDTO> getTransactionHistory(
            User user,
            UUID portfolioId,
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

    public record TransactionResult(TransactionResponseDTO response, boolean replayed) {
    }
}
