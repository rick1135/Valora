package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AssetRepositoryIntegrationTest {

    @Autowired
    private AssetRepository assetRepository;

    @BeforeEach
    void setUp() {
        assetRepository.deleteAll();
    }

    @Test
    void shouldEnforceCaseInsensitiveUniqueTicker() {
        Asset first = new Asset();
        first.setTicker("petr4");
        first.setName("Petrobras");
        first.setCategory(AssetCategory.ACOES);
        assetRepository.saveAndFlush(first);

        Asset second = new Asset();
        second.setTicker("PETR4");
        second.setName("Petrobras PN");
        second.setCategory(AssetCategory.ACOES);

        assertThatThrownBy(() -> assetRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
