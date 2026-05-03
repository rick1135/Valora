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
import com.rick1135.Valora.exception.InsufficientPositionException;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.mapper.TransactionMapper;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

    @Mock
    private PortfolioService portfolioService;

    @InjectMocks
    private TransactionService transactionService;

    @Test
    void processTransactionShouldReturnCreatedTransactionSnapshotOnBuy() {
        User user = new User();
        user.setId(UUID.randomUUID());
        Portfolio portfolio = portfolio(user);

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("PETR4");

        Instant now = Instant.now();
        TransactionDTO dto = new TransactionDTO(
                portfolio.getId(),
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

        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId())).thenReturn(portfolio);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(transactionMapper.toEntity(dto)).thenReturn(mappedTransaction);
        when(positionRepository.findByPortfolioAndAsset(portfolio, asset)).thenReturn(Optional.empty());
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
                            portfolio.getId(),
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
        Portfolio portfolio = portfolio(user);
        TransactionDTO dto = new TransactionDTO(
                portfolio.getId(),
                assetId,
                TransactionType.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10.00000000"),
                Instant.now()
        );

        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId())).thenReturn(portfolio);
        when(assetRepository.findById(assetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.processTransaction(user, dto))
                .isInstanceOf(AssetNotFoundException.class)
                .hasMessage("Ativo nao encontrado");
    }

    @Test
    void processTransactionShouldRejectPortfolioNotOwnedBeforeLoadingAsset() {
        User user = new User();
        Portfolio portfolio = portfolio(user);
        TransactionDTO dto = new TransactionDTO(
                portfolio.getId(),
                UUID.randomUUID(),
                TransactionType.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10.00000000"),
                Instant.now()
        );

        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId()))
                .thenThrow(new PortfolioNotFoundException("Carteira nao encontrada."));

        assertThatThrownBy(() -> transactionService.processTransaction(user, dto))
                .isInstanceOf(PortfolioNotFoundException.class);
        verifyNoInteractions(assetRepository, transactionRepository, positionRepository);
    }

    @Test
    void processTransactionShouldThrowWhenSellExceedsCurrentPosition() {
        User user = new User();
        Portfolio portfolio = portfolio(user);
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("VALE3");

        Position position = new Position();
        position.setPortfolio(portfolio);
        position.setAsset(asset);
        position.setQuantity(new BigDecimal("1.00000000"));
        position.setAveragePrice(new BigDecimal("50.00000000"));

        TransactionDTO dto = new TransactionDTO(
                portfolio.getId(),
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

        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId())).thenReturn(portfolio);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(transactionMapper.toEntity(dto)).thenReturn(mappedTransaction);
        when(positionRepository.findByPortfolioAndAsset(portfolio, asset)).thenReturn(Optional.of(position));
        when(transactionRepository.save(any(Transaction.class))).thenReturn(mappedTransaction);

        assertThatThrownBy(() -> transactionService.processTransaction(user, dto))
                .isInstanceOf(InsufficientPositionException.class)
                .hasMessageContaining("Quantidade insuficiente");
    }

    @Test
    void processTransactionShouldClearPurchaseDateWhenSellClosesPosition() {
        User user = new User();
        Portfolio portfolio = portfolio(user);
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("CDB001");

        Position position = new Position();
        position.setPortfolio(portfolio);
        position.setAsset(asset);
        position.setQuantity(new BigDecimal("5.00000000"));
        position.setAveragePrice(new BigDecimal("100.00000000"));
        position.setPurchaseDate(LocalDate.of(2025, 1, 10));

        TransactionDTO dto = new TransactionDTO(
                portfolio.getId(),
                asset.getId(),
                TransactionType.SELL,
                new BigDecimal("5.00000000"),
                new BigDecimal("110.00000000"),
                Instant.parse("2026-03-10T12:00:00Z")
        );

        Transaction mappedTransaction = new Transaction();
        mappedTransaction.setType(TransactionType.SELL);
        mappedTransaction.setQuantity(dto.quantity());
        mappedTransaction.setUnitPrice(dto.unitPrice());
        mappedTransaction.setTransactionDate(dto.transactionDate());

        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId())).thenReturn(portfolio);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(transactionMapper.toEntity(dto)).thenReturn(mappedTransaction);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(mappedTransaction);
        when(positionRepository.findByPortfolioAndAsset(portfolio, asset)).thenReturn(Optional.of(position));
        when(positionRepository.save(any(Position.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionMapper.toResponse(any(Transaction.class), any(Asset.class), any(Position.class)))
                .thenReturn(null);

        transactionService.processTransaction(user, dto);

        ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("0.00000000");
        assertThat(captor.getValue().getAveragePrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().getPurchaseDate()).isNull();
    }

    @Test
    void processTransactionShouldReplaceStalePurchaseDateWhenBuyingClosedPosition() {
        User user = new User();
        Portfolio portfolio = portfolio(user);
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("CDB002");

        Position position = new Position();
        position.setPortfolio(portfolio);
        position.setAsset(asset);
        position.setQuantity(BigDecimal.ZERO.setScale(8));
        position.setAveragePrice(BigDecimal.ZERO.setScale(8));
        position.setPurchaseDate(LocalDate.of(2024, 5, 1));

        TransactionDTO dto = new TransactionDTO(
                portfolio.getId(),
                asset.getId(),
                TransactionType.BUY,
                new BigDecimal("2.00000000"),
                new BigDecimal("100.00000000"),
                Instant.parse("2026-03-10T12:00:00Z")
        );

        Transaction mappedTransaction = new Transaction();
        mappedTransaction.setType(TransactionType.BUY);
        mappedTransaction.setQuantity(dto.quantity());
        mappedTransaction.setUnitPrice(dto.unitPrice());
        mappedTransaction.setTransactionDate(dto.transactionDate());

        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId())).thenReturn(portfolio);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(transactionMapper.toEntity(dto)).thenReturn(mappedTransaction);
        when(transactionRepository.save(any(Transaction.class))).thenReturn(mappedTransaction);
        when(positionRepository.findByPortfolioAndAsset(portfolio, asset)).thenReturn(Optional.of(position));
        when(positionRepository.save(any(Position.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionMapper.toResponse(any(Transaction.class), any(Asset.class), any(Position.class)))
                .thenReturn(null);

        transactionService.processTransaction(user, dto);

        ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo("2.00000000");
        assertThat(captor.getValue().getPurchaseDate()).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    void getTransactionHistoryShouldReturnPagedHistoryUsingNormalizedTickerFilter() {
        User user = new User();
        user.setId(UUID.randomUUID());
        Portfolio portfolio = portfolio(user);

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("PETR4");

        Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID());
        transaction.setAsset(asset);
        transaction.setType(TransactionType.BUY);
        transaction.setQuantity(new BigDecimal("2.00000000"));
        transaction.setUnitPrice(new BigDecimal("30.00000000"));
        transaction.setTransactionDate(Instant.parse("2026-04-01T10:00:00Z"));

        TransactionResponseDTO response = new TransactionResponseDTO(
                transaction.getId(),
                portfolio.getId(),
                asset.getId(),
                asset.getTicker(),
                transaction.getType(),
                transaction.getQuantity(),
                transaction.getUnitPrice(),
                transaction.getTransactionDate(),
                null,
                null
        );

        PageRequest pageable = PageRequest.of(0, 20);
        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId())).thenReturn(portfolio);
        when(transactionRepository.findTransactionHistoryByPortfolioAndFilters(portfolio, "PETR4", TransactionType.BUY, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(transaction), pageable, 1));
        when(transactionMapper.toHistoryResponse(transaction)).thenReturn(response);

        Page<TransactionResponseDTO> result = transactionService.getTransactionHistory(user, portfolio.getId(), " petr4 ", TransactionType.BUY, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().ticker()).isEqualTo("PETR4");
        assertThat(result.getContent().getFirst().type()).isEqualTo(TransactionType.BUY);
    }

    @Test
    void getTransactionHistoryShouldPassNullFiltersWhenNotProvided() {
        User user = new User();
        user.setId(UUID.randomUUID());
        Portfolio portfolio = portfolio(user);

        PageRequest pageable = PageRequest.of(1, 10);
        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId())).thenReturn(portfolio);
        when(transactionRepository.findTransactionHistoryByPortfolioAndFilters(portfolio, null, null, null, null, pageable))
                .thenReturn(Page.empty(pageable));

        Page<TransactionResponseDTO> result = transactionService.getTransactionHistory(user, portfolio.getId(), null, null, null, null, pageable);

        assertThat(result).isEmpty();
    }

    @Test
    void getTransactionHistoryShouldPassDateRangeFiltersToRepository() {
        User user = new User();
        user.setId(UUID.randomUUID());
        Portfolio portfolio = portfolio(user);

        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setTicker("VALE3");

        Instant startDate = Instant.parse("2026-03-01T00:00:00Z");
        Instant endDate = Instant.parse("2026-03-31T23:59:59Z");

        Transaction transaction = new Transaction();
        transaction.setId(UUID.randomUUID());
        transaction.setAsset(asset);
        transaction.setType(TransactionType.SELL);
        transaction.setQuantity(new BigDecimal("3.00000000"));
        transaction.setUnitPrice(new BigDecimal("60.00000000"));
        transaction.setTransactionDate(Instant.parse("2026-03-15T12:00:00Z"));

        TransactionResponseDTO response = new TransactionResponseDTO(
                transaction.getId(),
                portfolio.getId(),
                asset.getId(),
                asset.getTicker(),
                transaction.getType(),
                transaction.getQuantity(),
                transaction.getUnitPrice(),
                transaction.getTransactionDate(),
                null,
                null
        );

        PageRequest pageable = PageRequest.of(0, 20);
        when(portfolioService.resolveOwnedPortfolio(user, portfolio.getId())).thenReturn(portfolio);
        when(transactionRepository.findTransactionHistoryByPortfolioAndFilters(
                eq(portfolio),
                eq("VALE3"),
                eq(TransactionType.SELL),
                eq(startDate),
                eq(endDate),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(transaction), pageable, 1));
        when(transactionMapper.toHistoryResponse(transaction)).thenReturn(response);

        Page<TransactionResponseDTO> result = transactionService.getTransactionHistory(
                user,
                portfolio.getId(),
                "vale3",
                TransactionType.SELL,
                startDate,
                endDate,
                pageable
        );

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().transactionDate()).isBetween(startDate, endDate);
    }

    private Portfolio portfolio(User user) {
        Portfolio portfolio = new Portfolio();
        portfolio.setId(UUID.randomUUID());
        portfolio.setUser(user);
        portfolio.setName("Principal");
        return portfolio;
    }
}
