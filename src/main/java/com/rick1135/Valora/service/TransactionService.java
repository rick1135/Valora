package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.TransactionDTO;
import com.rick1135.Valora.dto.response.TransactionResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.exception.InsufficientPositionException;
import com.rick1135.Valora.mapper.TransactionMapper;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TransactionService {
    private final TransactionRepository transactionRepository;
    private final PositionRepository positionRepository;
    private final AssetRepository assetRepository;
    private final TransactionMapper transactionMapper;

    @Transactional
    public TransactionResponseDTO processTransaction(User user, TransactionDTO dto) {
        Asset asset = assetRepository.findById(dto.assetId())
                .orElseThrow(() -> new AssetNotFoundException("Ativo nao encontrado"));

        Transaction transaction = transactionMapper.toEntity(dto);
        transaction.setUser(user);
        transaction.setAsset(asset);
        Transaction savedTransaction = transactionRepository.save(transaction);

        Position position = positionRepository.findByUserAndAsset(user, asset)
                .orElseGet(() -> {
                    Position newPosition = new Position();
                    newPosition.setUser(user);
                    newPosition.setAsset(asset);
                    newPosition.setQuantity(BigDecimal.ZERO);
                    newPosition.setAveragePrice(BigDecimal.ZERO);
                    return newPosition;
                });

        if (dto.type() == TransactionType.BUY) {
            handleBuy(position, dto);
        } else if (dto.type() == TransactionType.SELL) {
            handleSell(position, dto);
        }

        Position savedPosition = positionRepository.save(position);
        return transactionMapper.toResponse(savedTransaction, asset, savedPosition);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponseDTO> getTransactionHistory(
            User user,
            String ticker,
            TransactionType type,
            Instant startDate,
            Instant endDate,
            Pageable pageable
    ) {
        String normalizedTicker = normalizeTicker(ticker);
        return transactionRepository.findTransactionHistoryByUserAndFilters(
                        user,
                        normalizedTicker,
                        type,
                        startDate,
                        endDate,
                        pageable
                )
                .map(transactionMapper::toHistoryResponse);
    }

    private void handleBuy(Position position, TransactionDTO dto) {
        BigDecimal currentQuantity = position.getQuantity();
        BigDecimal currentAvgPrice = position.getAveragePrice();

        BigDecimal buyQuantity = dto.quantity();
        BigDecimal buyAvgPrice = dto.unitPrice();

        BigDecimal currentTotalValue = currentQuantity.multiply(currentAvgPrice);
        BigDecimal buyTotalValue = buyQuantity.multiply(buyAvgPrice);
        BigDecimal newQuantity = currentQuantity.add(buyQuantity);

        BigDecimal newAvgPrice = currentTotalValue.add(buyTotalValue)
                .divide(newQuantity, 8, RoundingMode.HALF_UP);

        position.setQuantity(newQuantity);
        position.setAveragePrice(newAvgPrice);
    }

    private void handleSell(Position position, TransactionDTO dto) {
        if (position.getQuantity().compareTo(dto.quantity()) < 0) {
            throw new InsufficientPositionException("Quantidade insuficiente para venda");
        }

        BigDecimal newQuantity = position.getQuantity().subtract(dto.quantity());
        position.setQuantity(newQuantity);

        if (newQuantity.compareTo(BigDecimal.ZERO) == 0) {
            position.setAveragePrice(BigDecimal.ZERO);
        }
    }

    private String normalizeTicker(String ticker) {
        if (ticker == null) {
            return null;
        }

        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
}
