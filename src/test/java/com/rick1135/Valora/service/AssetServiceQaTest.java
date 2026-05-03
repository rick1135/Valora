package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.exception.AssetAlreadyExistsException;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.mapper.AssetMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
public class AssetServiceQaTest {

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private AssetMapper assetMapper;

    @InjectMocks
    private AssetService assetService;

    @Test
    public void issuerTooLongShouldNotBeConfusedWithAlreadyExists() {
        String longIssuer = "A".repeat(300);
        AssetRequestDTO request = new AssetRequestDTO("CDB1", "CDB", AssetCategory.RENDA_FIXA, FixedIncomeIndexer.CDI, new BigDecimal("10.0"), longIssuer, LocalDate.now());
        
        when(assetRepository.findByTickerIgnoreCase(any())).thenReturn(Optional.empty());
        when(assetMapper.toEntity(any())).thenReturn(new com.rick1135.Valora.entity.Asset());
        when(assetRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Value too long for column 'issuer'", new java.sql.SQLException("Value too long for column 'issuer'", "22001")));
        
        assertThatThrownBy(() -> assetService.createAsset(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Erro de integridade de dados ao salvar o ativo");
    }
}
