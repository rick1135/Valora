package com.rick1135.Valora.repository;

import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class PortfolioTargetIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<TestIds> createdRecords = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (TestIds ids : createdRecords) {
            jdbcTemplate.update("DELETE FROM portfolio_targets WHERE portfolio_id = ?", ids.portfolioId());
            jdbcTemplate.update("DELETE FROM portfolios WHERE id = ?", ids.portfolioId());
            jdbcTemplate.update("DELETE FROM assets WHERE id = ?", ids.assetId());
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", ids.userId());
        }
        createdRecords.clear();
    }

    @Test
    void shouldRejectPortfolioTargetWithBothCategoryAndAssetFilled() {
        TestIds ids = createBaseRecords("both-filled");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO portfolio_targets (id, portfolio_id, asset_category, asset_id, target_percentage)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), ids.portfolioId(), "ACOES", ids.assetId(), new java.math.BigDecimal("100.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectPortfolioTargetWithBothCategoryAndAssetNull() {
        TestIds ids = createBaseRecords("both-null");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO portfolio_targets (id, portfolio_id, asset_category, asset_id, target_percentage)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), ids.portfolioId(), null, null, new java.math.BigDecimal("100.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TestIds createBaseRecords(String suffix) {
        UUID userId = UUID.randomUUID();
        UUID portfolioId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        String ticker = uniqueTicker("PTG");

        jdbcTemplate.update("""
                        INSERT INTO users (id, email, password_hash, name, role, email_verified)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                userId, "target-" + suffix + "-" + userId + "@valora.test", "hash", "Target Test", "USER", true);

        jdbcTemplate.update("""
                        INSERT INTO portfolios (id, user_id, name, description)
                        VALUES (?, ?, ?, ?)
                        """,
                portfolioId, userId, "Portfolio " + suffix, null);

        jdbcTemplate.update("""
                        INSERT INTO assets (id, ticker, ticker_normalized, name, category)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                assetId, ticker, ticker, "Target Asset", "ACOES");

        TestIds ids = new TestIds(userId, portfolioId, assetId);
        createdRecords.add(ids);
        return ids;
    }

    private record TestIds(UUID userId, UUID portfolioId, UUID assetId) {
    }
}
