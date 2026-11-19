package com.rick1135.Valora.repository;

import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class AssetRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private AssetRepository assetRepository;

    private UUID createdAssetId;

    @AfterEach
    void tearDown() {
        if (createdAssetId != null) {
            assetRepository.deleteById(createdAssetId);
            createdAssetId = null;
        }
    }

    @Test
    void shouldEnforceCaseInsensitiveUniqueTicker() {
        String ticker = uniqueTicker("ARI");

        Asset first = new Asset();
        first.setTicker(ticker.toLowerCase(Locale.ROOT));
        first.setName("Petrobras");
        first.setCategory(AssetCategory.ACOES);
        createdAssetId = assetRepository.saveAndFlush(first).getId();

        Asset second = new Asset();
        second.setTicker(ticker.toUpperCase(Locale.ROOT));
        second.setName("Petrobras PN");
        second.setCategory(AssetCategory.ACOES);

        assertThatThrownBy(() -> assetRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
