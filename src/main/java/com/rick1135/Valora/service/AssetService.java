package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
import com.rick1135.Valora.exception.AssetAlreadyExistsException;
import com.rick1135.Valora.mapper.AssetMapper;
import com.rick1135.Valora.repository.AssetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

@Service
public class AssetService {
    private static final String REQUIRED_FIXED_INCOME_FIELDS =
            "Indexador, taxa anual, emissor e data de vencimento sao obrigatorios para ativos de renda fixa.";

    private final AssetRepository assetRepository;
    private final AssetMapper assetMapper;
    private final MarketCalendar marketCalendar;

    @Autowired
    public AssetService(AssetRepository assetRepository, AssetMapper assetMapper, MarketCalendar marketCalendar) {
        this.assetRepository = assetRepository;
        this.assetMapper = assetMapper;
        this.marketCalendar = marketCalendar != null ? marketCalendar : new MarketCalendar(Clock.systemDefaultZone());
    }

    public AssetService(AssetRepository assetRepository, AssetMapper assetMapper) {
        this(assetRepository, assetMapper, new MarketCalendar(Clock.systemDefaultZone()));
    }

    public AssetResponseDTO createAsset(AssetRequestDTO request) {
        FixedIncomeRateType rateType = request.rateType();
        if (request.category() == AssetCategory.RENDA_FIXA) {
            if (request.indexer() == null || request.issuer() == null || request.expirationDate() == null) {
                throw new IllegalArgumentException(REQUIRED_FIXED_INCOME_FIELDS);
            }
            if (rateType == null && request.indexer() == FixedIncomeIndexer.PRE) {
                rateType = FixedIncomeRateType.FIXED;
            }
            if (request.expirationDate().isBefore(marketCalendar.today())) {
                throw new IllegalArgumentException("A data de vencimento nao pode estar no passado.");
            }
            validateRates(request, rateType);
        } else if (request.indexer() != null || request.annualRate() != null || rateType != null
                || request.spreadRate() != null || (request.issuer() != null && !request.issuer().isBlank())
                || request.expirationDate() != null) {
            throw new IllegalArgumentException(
                    "Campos de renda fixa so podem ser informados para ativos da categoria RENDA_FIXA.");
        }

        String normalizedTicker = normalizeTicker(request.ticker());

        if (assetRepository.findByTickerIgnoreCase(normalizedTicker).isPresent()) {
            throw new AssetAlreadyExistsException("Ativo com ticker '" + normalizedTicker + "' ja cadastrado.");
        }

        Asset asset = assetMapper.toEntity(request);
        asset.setTicker(normalizedTicker);
        asset.setName(request.name().trim());
        asset.setRateType(rateType);
        asset.setSpreadRate(request.spreadRate());

        try {
            Asset savedAsset = assetRepository.saveAndFlush(asset);
            return assetMapper.toResponse(savedAsset);
        } catch (DataIntegrityViolationException exception) {
            Throwable rootCause = exception.getMostSpecificCause();
            if (rootCause instanceof java.sql.SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if ("23505".equals(sqlState)) {
                    throw new AssetAlreadyExistsException("Ativo com ticker '" + normalizedTicker + "' ja cadastrado.");
                }
            }
            throw new IllegalArgumentException("Erro de integridade de dados ao salvar o ativo: " + (rootCause != null ? rootCause.getMessage() : ""));
        }
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> getAllAssets(int page, int size, String sort, String direction) {
        Sort.Direction sortDirection = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        String safeSortField = isSortableField(sort) ? sort : "ticker";
        PageRequest pageable = PageRequest.of(page, size, Sort.by(sortDirection, safeSortField));
        return assetRepository.findAll(pageable)
                .stream()
                .map(assetMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> searchAssets(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        return assetRepository.findByTickerContainingIgnoreCase(normalizedTicker)
                .stream()
                .map(assetMapper::toResponse)
                .toList();
    }

    private void validateRates(AssetRequestDTO request, FixedIncomeRateType rateType) {
        BigDecimal annualRate = request.annualRate();
        BigDecimal spreadRate = request.spreadRate();
        FixedIncomeIndexer indexer = request.indexer();

        switch (rateType) {
            case null -> {
                if (spreadRate != null) {
                    throw new IllegalArgumentException("Informe rateType quando spreadRate for informado.");
                }
                validateAnnualRate(annualRate);
            }
            case FIXED -> {
                validateAnnualRate(annualRate);
                if (indexer != FixedIncomeIndexer.PRE || (spreadRate != null && spreadRate.signum() != 0)) {
                    throw new IllegalArgumentException("A taxa fixa exige indexador PRE e spread nulo ou zero.");
                }
            }
            case PERCENTAGE_OF_INDEX -> {
                validateAnnualRate(annualRate);
                if (indexer != FixedIncomeIndexer.CDI && indexer != FixedIncomeIndexer.SELIC) {
                    throw new IllegalArgumentException("O percentual do indexador exige CDI ou SELIC.");
                }
                if (annualRate.signum() == 0) {
                    throw new IllegalArgumentException("A taxa anual deve ser maior que zero para percentual do indexador.");
                }
                if (spreadRate != null && spreadRate.signum() != 0) {
                    throw new IllegalArgumentException("Percentual do indexador nao pode ser combinado com spread.");
                }
            }
            case SPREAD -> {
                if (indexer == FixedIncomeIndexer.PRE) {
                    throw new IllegalArgumentException("O spread exige indexador CDI, SELIC ou IPCA.");
                }
                if (annualRate != null) {
                    throw new IllegalArgumentException("Informe apenas spreadRate quando o tipo de taxa for SPREAD.");
                }
                if (spreadRate == null || spreadRate.signum() < 0) {
                    throw new IllegalArgumentException("O spread e obrigatorio e nao pode ser negativo.");
                }
            }
        }
    }

    private void validateAnnualRate(BigDecimal annualRate) {
        if (annualRate == null) {
            throw new IllegalArgumentException(REQUIRED_FIXED_INCOME_FIELDS);
        }
        if (annualRate.signum() < 0) {
            throw new IllegalArgumentException("A taxa anual nao pode ser negativa.");
        }
    }

    private String normalizeTicker(String ticker) {
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isSortableField(String field) {
        return "ticker".equalsIgnoreCase(field)
                || "name".equalsIgnoreCase(field)
                || "category".equalsIgnoreCase(field);
    }
}
