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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private TransactionMapper transactionMapper;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void processTransactionShouldReturnCreatedTransactionSnapshotOnBuy() {
        User user = new User();
        user.setId(UUID.randomUUID());

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("PETR4");

        Instant now = Instant.now();
        TransactionDTO dto = new TransactionDTO(
                asset.getId(),
                TransactionType.BUY,
                new BigDecimal("10.00000000"),
                new BigDecimal("25.00000000"),
                now
        );

        Transaction mappedTransaction = new Transaction();
        mappedTransaction.setType(TransactionType.BUY);
        mappedTransaction.setQuantity(new BigDecimal("10.00000000"));
        mappedTransaction.setUnitPrice(new BigDecimal("25.00000000"));
        mappedTransaction.setTransactionDate(now);

        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(transactionMapper.toEntity(dto)).thenReturn(mappedTransaction);
        when(positionRepository.findByUserAndAsset(user, asset)).thenReturn(Optional.empty());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction transaction = invocation.getArgument(0);
            transaction.setId(UUID.randomUUID());
            return transaction;
        });
        when(positionRepository.save(any(Position.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionMapper.toResponse(any(Transaction.class), any(Asset.class), any(Position.class)))
                .thenAnswer(invocation -> {
                    Transaction transaction = invocation.getArgument(0);
                    Asset mappedAsset = invocation.getArgument(1);
                    Position position = invocation.getArgument(2);
                    return new TransactionResponseDTO(
                            transaction.getId(),
                            mappedAsset.getId(),
                            mappedAsset.getTicker(),
                            transaction.getType(),
                            transaction.getQuantity(),
                            transaction.getUnitPrice(),
                            transaction.getTransactionDate(),
                            position.getQuantity(),
                            position.getAveragePrice()
                    );
                });

        TransactionResponseDTO response = transactionService.processTransaction(user, dto);

        assertThat(response.transactionId()).isNotNull();
        assertThat(response.assetId()).isEqualTo(asset.getId());
        assertThat(response.ticker()).isEqualTo("PETR4");
        assertThat(response.type()).isEqualTo(TransactionType.BUY);
        assertThat(response.positionQuantity()).isEqualByComparingTo("10.00000000");
        assertThat(response.averagePrice()).isEqualByComparingTo("25.00000000");
    }

    @Test
    void processTransactionShouldThrowNotFoundWhenAssetDoesNotExist() {
        User user = new User();
        UUID assetId = UUID.randomUUID();
        TransactionDTO dto = new TransactionDTO(
                assetId,
                TransactionType.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10.00000000"),
                Instant.now()
        );

        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.processTransaction(user, dto))
                .isInstanceOf(AssetNotFoundException.class)
                .hasMessage("Ativo nao encontrado");
    }

    @Test
    void processTransactionShouldThrowWhenSellExceedsCurrentPosition() {
        User user = new User();
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("VALE3");

        Position position = new Position();
        position.setUser(user);
        position.setAsset(asset);
        position.setQuantity(new BigDecimal("1.00000000"));
        position.setAveragePrice(new BigDecimal("50.00000000"));

        TransactionDTO dto = new TransactionDTO(
                asset.getId(),
                TransactionType.SELL,
                new BigDecimal("2.00000000"),
                new BigDecimal("55.00000000"),
                Instant.now()
        );

        Transaction mappedTransaction = new Transaction();
        mappedTransaction.setType(TransactionType.SELL);
        mappedTransaction.setQuantity(dto.quantity());
        mappedTransaction.setUnitPrice(dto.unitPrice());
        mappedTransaction.setTransactionDate(dto.transactionDate());

        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(transactionMapper.toEntity(dto)).thenReturn(mappedTransaction);
        when(positionRepository.findByUserAndAsset(user, asset)).thenReturn(Optional.of(position));
        when(transactionRepository.save(any(Transaction.class))).thenReturn(mappedTransaction);

        assertThatThrownBy(() -> transactionService.processTransaction(user, dto))
                .isInstanceOf(InsufficientPositionException.class)
                .hasMessageContaining("Quantidade insuficiente");
    }
}
