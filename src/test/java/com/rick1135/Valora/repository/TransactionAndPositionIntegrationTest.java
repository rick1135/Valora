package com.rick1135.Valora.repository;

import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;

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

    private User user;
    private Portfolio portfolio;
    private Asset asset;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        positionRepository.deleteAll();
        portfolioRepository.deleteAll();
        assetRepository.deleteAll();
        userRepository.deleteAll();

        user = new User();
        user.setEmail("investor@valora.dev");
        user.setPasswordHash("hash");
        user.setName("Investor");
        user.setRole(UserRole.USER);
        user = userRepository.saveAndFlush(user);

        portfolio = new Portfolio();
        portfolio.setUser(user);
        portfolio.setName("Principal");
        portfolio = portfolioRepository.saveAndFlush(portfolio);

        asset = new Asset();
        asset.setTicker("PETR4");
        asset.setName("Petrobras");
        asset.setCategory(AssetCategory.ACOES);
        asset = assetRepository.saveAndFlush(asset);
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
