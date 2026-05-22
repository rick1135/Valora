package com.rick1135.Valora.repository;

import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class PortfolioTargetIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

        jdbcTemplate.update("""
                        INSERT INTO users (id, email, password_hash, name, role, email_verified)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                userId, "target-" + suffix + "@valora.test", "hash", "Target Test", "USER", true);

        jdbcTemplate.update("""
                        INSERT INTO portfolios (id, user_id, name, description)
                        VALUES (?, ?, ?, ?)
                        """,
                portfolioId, userId, "Portfolio " + suffix, null);

        jdbcTemplate.update("""
                        INSERT INTO assets (id, ticker, ticker_normalized, name, category)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                assetId, "T" + suffix.replace("-", "").toUpperCase(), "T" + suffix.replace("-", "").toUpperCase(), "Target Asset", "ACOES");

        return new TestIds(portfolioId, assetId);
    }

    private record TestIds(UUID portfolioId, UUID assetId) {
    }
}
