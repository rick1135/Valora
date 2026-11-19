package com.rick1135.Valora.repository;

import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.dto.request.TransactionDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.exception.InsufficientPositionException;
import com.rick1135.Valora.service.TransactionService;
import com.rick1135.Valora.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class TransactionAndPositionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionService transactionService;

    private User user;
    private Portfolio portfolio;
    private Asset asset;
    private Portfolio targetPortfolio;
    private Asset targetAsset;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setEmail("txp-" + UUID.randomUUID() + "@valora.dev");
        user.setPasswordHash("hash");
        user.setName("Investor");
        user.setRole(UserRole.USER);
        user = userRepository.saveAndFlush(user);

        portfolio = new Portfolio();
        portfolio.setUser(user);
        portfolio.setName("Principal");
        portfolio = portfolioRepository.saveAndFlush(portfolio);

        asset = new Asset();
        asset.setTicker(uniqueTicker("TXP"));
        asset.setName("Petrobras");
        asset.setCategory(AssetCategory.ACOES);
        asset = assetRepository.saveAndFlush(asset);
    }

    @AfterEach
    void tearDown() {
        deletePortfolio(targetPortfolio);
        deletePortfolio(portfolio);
        if (targetAsset != null) {
            assetRepository.deleteById(targetAsset.getId());
        }
        if (asset != null) {
            assetRepository.deleteById(asset.getId());
        }
        if (user != null) {
            userRepository.deleteById(user.getId());
        }
    }

    private void deletePortfolio(Portfolio portfolio) {
        if (portfolio != null) {
            jdbcTemplate.update("DELETE FROM transactions WHERE portfolio_id = ?", portfolio.getId());
            jdbcTemplate.update("DELETE FROM positions WHERE portfolio_id = ?", portfolio.getId());
            portfolioRepository.deleteById(portfolio.getId());
        }
    }

    @Test
    void shouldCreateOnlyOneTransactionForConcurrentRequestsWithSameKey() throws Exception {
        TransactionDTO dto = new TransactionDTO(portfolio.getId(), asset.getId(), TransactionType.BUY,
                new BigDecimal("10.000000001"), BigDecimal.ONE, Instant.parse("2026-01-10T15:00:00.000000001Z"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return transactionService.processTransaction(user, dto, "  order-1 ");
            });
            var second = executor.submit(() -> {
                start.await();
                return transactionService.processTransaction(user, dto, "order-1");
            });
            start.countDown();

            var firstResult = first.get(15, TimeUnit.SECONDS);
            var secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.replayed(), secondResult.replayed())).containsExactlyInAnyOrder(false, true);
            assertThat(firstResult.response().transactionId()).isEqualTo(secondResult.response().transactionId());
        }

        assertThat(transactionRepository.findByPortfolioOrderByTransactionDateAscIdAsc(portfolio)).hasSize(1);
        assertThat(transactionRepository.findByPortfolioAndIdempotencyKey(portfolio, "order-1")).isPresent();
        assertThat(positionRepository.findByPortfolioAndAsset(portfolio, asset).orElseThrow().getQuantity())
                .isEqualByComparingTo(BigDecimal.TEN);
        TransactionDTO differentQuantity = new TransactionDTO(dto.portfolioId(), dto.assetId(), dto.type(),
                BigDecimal.TEN, dto.unitPrice(), dto.transactionDate());
        assertThatThrownBy(() -> transactionService.processTransaction(user, differentQuantity, "order-1"))
                .isInstanceOf(IllegalArgumentException.class);

        targetPortfolio = createTargetPortfolio();
        Transaction sameKeyInOtherPortfolio = new Transaction();
        sameKeyInOtherPortfolio.setPortfolio(targetPortfolio);
        sameKeyInOtherPortfolio.setAsset(asset);
        sameKeyInOtherPortfolio.setType(dto.type());
        sameKeyInOtherPortfolio.setQuantity(dto.quantity());
        sameKeyInOtherPortfolio.setUnitPrice(dto.unitPrice());
        sameKeyInOtherPortfolio.setTransactionDate(dto.transactionDate());
        sameKeyInOtherPortfolio.setIdempotencyKey("order-1");
        transactionRepository.saveAndFlush(sameKeyInOtherPortfolio);

        Transaction duplicate = new Transaction();
        duplicate.setPortfolio(targetPortfolio);
        duplicate.setAsset(asset);
        duplicate.setType(dto.type());
        duplicate.setQuantity(dto.quantity());
        duplicate.setUnitPrice(dto.unitPrice());
        duplicate.setTransactionDate(dto.transactionDate());
        duplicate.setIdempotencyKey("order-1");
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRebuildBothPositionsAfterMoveAndZeroPositionAfterDeletion() {
        targetPortfolio = createTargetPortfolio();
        targetAsset = new Asset();
        targetAsset.setTicker(uniqueTicker("TXP"));
        targetAsset.setName("Target asset");
        targetAsset.setCategory(AssetCategory.ACOES);
        targetAsset = assetRepository.saveAndFlush(targetAsset);

        Instant originalDate = Instant.parse("2026-01-10T15:00:00Z");
        UUID transactionId = transactionService.processTransaction(user,
                new TransactionDTO(portfolio.getId(), asset.getId(), TransactionType.BUY,
                        BigDecimal.TEN, BigDecimal.ONE, originalDate)).transactionId();

        Instant updatedDate = Instant.parse("2026-01-15T15:00:00Z");
        var updated = transactionService.updateTransaction(user, transactionId,
                new TransactionDTO(targetPortfolio.getId(), targetAsset.getId(), TransactionType.BUY,
                        new BigDecimal("5"), new BigDecimal("12"), updatedDate));

        assertThat(updated.transactionId()).isEqualTo(transactionId);
        Transaction moved = transactionRepository.findById(transactionId).orElseThrow();
        assertThat(moved.getPortfolio().getId()).isEqualTo(targetPortfolio.getId());
        assertThat(moved.getAsset().getId()).isEqualTo(targetAsset.getId());
        assertThat(moved.getTransactionDate()).isEqualTo(updatedDate);
        assertEmptyPosition(portfolio, asset);
        Position targetPosition = positionRepository.findByPortfolioAndAsset(targetPortfolio, targetAsset).orElseThrow();
        assertThat(targetPosition.getQuantity()).isEqualByComparingTo("5");
        assertThat(targetPosition.getAveragePrice()).isEqualByComparingTo("12");
        assertThat(targetPosition.getPurchaseDate()).isEqualTo(LocalDate.of(2026, 1, 15));

        transactionService.deleteTransaction(user, transactionId);

        assertThat(transactionRepository.findById(transactionId)).isEmpty();
        assertEmptyPosition(targetPortfolio, targetAsset);
    }

    @Test
    void shouldKeepOriginalTransactionAndPositionWhenEditOrDeletionInvalidatesLaterSale() {
        Instant buyDate = Instant.parse("2026-01-10T15:00:00Z");
        UUID buyId = transactionService.processTransaction(user,
                new TransactionDTO(portfolio.getId(), asset.getId(), TransactionType.BUY,
                        BigDecimal.TEN, BigDecimal.ONE, buyDate)).transactionId();
        transactionService.processTransaction(user,
                new TransactionDTO(portfolio.getId(), asset.getId(), TransactionType.SELL,
                        new BigDecimal("3"), BigDecimal.ONE, buyDate.plusSeconds(3600)));

        assertThatThrownBy(() -> transactionService.updateTransaction(user, buyId,
                new TransactionDTO(portfolio.getId(), asset.getId(), TransactionType.BUY,
                        BigDecimal.ONE, BigDecimal.ONE, buyDate)))
                .isInstanceOf(InsufficientPositionException.class);
        assertThatThrownBy(() -> transactionService.deleteTransaction(user, buyId))
                .isInstanceOf(InsufficientPositionException.class);

        assertThat(transactionRepository.findById(buyId).orElseThrow().getQuantity()).isEqualByComparingTo("10");
        assertThat(transactionRepository.findByPortfolioOrderByTransactionDateAscIdAsc(portfolio)).hasSize(2);
        assertThat(positionRepository.findByPortfolioAndAsset(portfolio, asset).orElseThrow().getQuantity())
                .isEqualByComparingTo("7");
    }

    private Portfolio createTargetPortfolio() {
        Portfolio target = new Portfolio();
        target.setUser(user);
        target.setName("Destino");
        return portfolioRepository.saveAndFlush(target);
    }

    private void assertEmptyPosition(Portfolio portfolio, Asset asset) {
        Position position = positionRepository.findByPortfolioAndAsset(portfolio, asset).orElseThrow();
        assertThat(position.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(position.getAveragePrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(position.getPurchaseDate()).isNull();
    }

    @Test
    void shouldPersistValidTransactionWithInstantDate() {
        Transaction transaction = new Transaction();
        transaction.setPortfolio(portfolio);
        transaction.setAsset(asset);
        transaction.setType(TransactionType.BUY);
        transaction.setQuantity(new BigDecimal("10.00000000"));
        transaction.setUnitPrice(new BigDecimal("35.50000000"));
        transaction.setTransactionDate(Instant.now());

        Transaction saved = transactionRepository.saveAndFlush(transaction);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTransactionDate()).isNotNull();
    }

    @Test
    void shouldRejectNullTransactionTypeByDatabaseConstraint() {
        Transaction transaction = new Transaction();
        transaction.setPortfolio(portfolio);
        transaction.setAsset(asset);
        transaction.setQuantity(new BigDecimal("1.00000000"));
        transaction.setUnitPrice(new BigDecimal("10.00000000"));
        transaction.setTransactionDate(Instant.now());

        assertThatThrownBy(() -> transactionRepository.saveAndFlush(transaction))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectNegativeTransactionQuantityByDatabaseCheck() {
        Transaction transaction = new Transaction();
        transaction.setPortfolio(portfolio);
        transaction.setAsset(asset);
        transaction.setType(TransactionType.BUY);
        transaction.setQuantity(new BigDecimal("-1.00000000"));
        transaction.setUnitPrice(new BigDecimal("10.00000000"));
        transaction.setTransactionDate(Instant.now());

        assertThatThrownBy(() -> transactionRepository.saveAndFlush(transaction))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldFailWithOptimisticLockWhenUpdatingStalePosition() {
        Position position = new Position();
        position.setPortfolio(portfolio);
        position.setAsset(asset);
        position.setQuantity(new BigDecimal("10.00000000"));
        position.setAveragePrice(new BigDecimal("30.00000000"));
        Position saved = positionRepository.saveAndFlush(position);

        Position firstRead = positionRepository.findById(saved.getId()).orElseThrow();
        Position staleRead = positionRepository.findById(saved.getId()).orElseThrow();

        firstRead.setQuantity(new BigDecimal("12.00000000"));
        Position updated = positionRepository.saveAndFlush(firstRead);
        assertThat(updated.getVersion()).isGreaterThan(0L);

        staleRead.setQuantity(new BigDecimal("8.00000000"));
        assertThatThrownBy(() -> positionRepository.saveAndFlush(staleRead))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
