package com.rick1135.Valora.service;

import com.rick1135.Valora.client.BrapiClient;
import com.rick1135.Valora.dto.brapi.BrapiResponseDTO;
import com.rick1135.Valora.dto.brapi.BrapiResultDTO;
import com.rick1135.Valora.dto.request.AssetImportRequestDTO;
import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.request.FixedIncomeAssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.FixedIncomeIndexer;
import com.rick1135.Valora.entity.FixedIncomeRateType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.AssetAlreadyExistsException;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.mapper.AssetMapper;
import com.rick1135.Valora.repository.AssetRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class AssetService {
    private static final String REQUIRED_FIXED_INCOME_FIELDS =
            "Indexador, taxa anual, emissor e data de vencimento sao obrigatorios para ativos de renda fixa.";
    private static final Pattern BRAPI_TICKER = Pattern.compile("^[A-Z0-9]{4,12}$", Pattern.CASE_INSENSITIVE);

    private final AssetRepository assetRepository;
    private final AssetMapper assetMapper;
    private final MarketCalendar marketCalendar;
    private final BrapiClient brapiClient;
    private final String brapiToken;

    @Autowired
    public AssetService(AssetRepository assetRepository, AssetMapper assetMapper, MarketCalendar marketCalendar,
                        @Nullable BrapiClient brapiClient, @Value("${brapi.token:}") String brapiToken) {
        this.assetRepository = assetRepository;
        this.assetMapper = assetMapper;
        this.marketCalendar = marketCalendar != null ? marketCalendar : new MarketCalendar(Clock.systemDefaultZone());
        this.brapiClient = brapiClient;
        this.brapiToken = brapiToken;
    }

    public AssetService(AssetRepository assetRepository, AssetMapper assetMapper, MarketCalendar marketCalendar) {
        this(assetRepository, assetMapper, marketCalendar, null, "");
    }

    public AssetService(AssetRepository assetRepository, AssetMapper assetMapper) {
        this(assetRepository, assetMapper, new MarketCalendar(Clock.systemDefaultZone()));
    }

    public AssetResponseDTO importAssetFromBrapi(AssetImportRequestDTO request) {
        if (request.category() == AssetCategory.RENDA_FIXA) {
            throw new IllegalArgumentException("Para cadastrar renda fixa, use /assets/fixed-income.");
        }
        if (request.ticker() == null || !BRAPI_TICKER.matcher(request.ticker()).matches()) {
            throw new IllegalArgumentException("Ticker invalido. Use entre 4 e 12 caracteres alfanumericos.");
        }
        String ticker = normalizeTicker(request.ticker());

        var existing = assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull(ticker);
        if (existing.isPresent()) {
            return assetMapper.toResponse(existing.get());
        }

        BrapiResultDTO quote = findBrapiQuote(ticker);
        if (quote.symbol() == null || !normalizeTicker(quote.symbol()).equals(ticker)) {
            throw new AssetNotFoundException("Ativo '" + ticker + "' nao encontrado na BRAPI.");
        }
        String name = ticker;
        if (StringUtils.hasText(quote.longName())) {
            name = quote.longName().strip();
        } else if (StringUtils.hasText(quote.shortName())) {
            name = quote.shortName().strip();
        }
        AssetCategory category = inferCategory(ticker, name);
        Asset asset = new Asset();
        asset.setTicker(ticker);
        asset.setName(name);
        asset.setCategory(category);

        try {
            asset = assetRepository.saveAndFlush(asset);
        } catch (DataIntegrityViolationException exception) {
            // saveAndFlush runs in its own repository transaction before the retry.
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            asset = assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull(ticker).orElseThrow(() -> exception);
        }
        return assetMapper.toResponse(asset);
    }

    public AssetResponseDTO createFixedIncomeAsset(User user, FixedIncomeAssetRequestDTO request) {
        Objects.requireNonNull(user, "Usuario autenticado e obrigatorio.");
        return createAsset(new AssetRequestDTO(request.ticker(), request.name(), AssetCategory.RENDA_FIXA,
                request.indexer(), request.annualRate(), request.issuer(), request.expirationDate(),
                request.rateType(), request.spreadRate()), user);
    }

    public AssetResponseDTO createAsset(AssetRequestDTO request) {
        return createAsset(request, null);
    }

    private AssetResponseDTO createAsset(AssetRequestDTO request, @Nullable User ownerUser) {
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

        if (assetRepository.findByTickerIgnoreCaseAndOwnerUserIsNull(normalizedTicker).isPresent()
                || (ownerUser != null
                    && assetRepository.findByTickerIgnoreCaseAndOwnerUser(normalizedTicker, ownerUser).isPresent())) {
            throw new AssetAlreadyExistsException("Ativo com ticker '" + normalizedTicker + "' ja cadastrado.");
        }

        Asset asset = assetMapper.toEntity(request);
        asset.setOwnerUser(ownerUser);
        asset.setTicker(normalizedTicker);
        asset.setName(request.name().trim());
        asset.setRateType(rateType);
        asset.setSpreadRate(request.spreadRate());

        try {
            Asset savedAsset = assetRepository.saveAndFlush(asset);
            return assetMapper.toResponse(savedAsset);
        } catch (DataIntegrityViolationException exception) {
            Throwable rootCause = exception.getMostSpecificCause();
            if (isUniqueViolation(exception)) {
                throw new AssetAlreadyExistsException("Ativo com ticker '" + normalizedTicker + "' ja cadastrado.");
            }
            throw new IllegalArgumentException("Erro de integridade de dados ao salvar o ativo: " + (rootCause != null ? rootCause.getMessage() : ""));
        }
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> getAllAssets(int page, int size, String sort, String direction) {
        return getAllAssets(null, page, size, sort, direction);
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> getAllAssets(@Nullable User user, int page, int size, String sort, String direction) {
        Sort.Direction sortDirection = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        String safeSortField = isSortableField(sort) ? sort : "ticker";
        PageRequest pageable = PageRequest.of(page, size, Sort.by(sortDirection, safeSortField));
        var assets = user == null ? assetRepository.findAll(pageable) : assetRepository.findVisibleToUser(user, pageable);
        return assets
                .stream()
                .map(assetMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> searchAssets(String ticker) {
        return searchAssets(null, ticker);
    }

    @Transactional(readOnly = true)
    public List<AssetResponseDTO> searchAssets(@Nullable User user, String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        var assets = user == null ? assetRepository.findByTickerContainingIgnoreCase(normalizedTicker)
                : assetRepository.searchVisibleToUser(user, normalizedTicker);
        return assets
                .stream()
                .map(assetMapper::toResponse)
                .toList();
    }

    private BrapiResultDTO findBrapiQuote(String ticker) {
        if (brapiClient == null || !StringUtils.hasText(brapiToken)) {
            throw new IllegalArgumentException("Cliente ou token da BRAPI indisponivel para importar ativos.");
        }
        String notFoundMessage = "Ativo '" + ticker + "' nao encontrado na BRAPI.";
        BrapiResponseDTO response;
        try {
            response = brapiClient.getQuote(ticker, brapiToken);
        } catch (RestClientException exception) {
            if (exception instanceof RestClientResponseException httpException
                    && httpException.getStatusCode().value() == 404) {
                throw new AssetNotFoundException(notFoundMessage);
            }
            throw new IllegalArgumentException("Nao foi possivel consultar o ativo '" + ticker + "' na BRAPI.", exception);
        }
        if (response == null || response.results() == null || response.results().isEmpty()
                || response.results().getFirst() == null) {
            throw new AssetNotFoundException(notFoundMessage);
        }
        return response.results().getFirst();
    }

    private AssetCategory inferCategory(String ticker, String name) {
        String normalizedName = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT);
        if (normalizedName.contains("ETF") || normalizedName.contains("ISHARES") || normalizedName.contains("INDEX")
                || normalizedName.contains("INDICE")) {
            return AssetCategory.ETF;
        }
        if (ticker.endsWith("11") && (normalizedName.contains("FII") || normalizedName.contains("IMOB"))) {
            return AssetCategory.FII;
        }
        return AssetCategory.ACOES;
    }

    private boolean isUniqueViolation(DataIntegrityViolationException exception) {
        return exception.getMostSpecificCause() instanceof SQLException sqlException
                && "23505".equals(sqlException.getSQLState());
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
