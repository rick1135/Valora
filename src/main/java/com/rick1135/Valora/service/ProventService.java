package com.rick1135.Valora.service;

import com.rick1135.Valora.common.FinancialConstants;
import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.dto.response.ProventProvisionResponseDTO;
import com.rick1135.Valora.dto.response.ProventResponseDTO;
import com.rick1135.Valora.entity.*;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.exception.ProventAlreadyExistsException;
import com.rick1135.Valora.mapper.ProventMapper;
import com.rick1135.Valora.repository.*;
import com.rick1135.Valora.repository.projection.UserAssetHoldingProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProventService {
    private static final LocalDate JCP_LC_224_2025_EFFECTIVE_DATE = LocalDate.of(2026, 1, 1);
    private static final BigDecimal JCP_WITHHOLDING_TAX_RATE_BEFORE_2026 = new BigDecimal("0.15");
    private static final BigDecimal JCP_WITHHOLDING_TAX_RATE_FROM_2026 = new BigDecimal("0.175");

    private final AssetRepository assetRepository;
    private final ProventRepository proventRepository;
    private final ProventProvisionRepository proventProvisionRepository;
    private final TransactionRepository transactionRepository;
    private final PortfolioRepository portfolioRepository;
    private final PortfolioService portfolioService;
    private final ProventMapper proventMapper;
    private final MarketCalendar marketCalendar;

    @Autowired(required = false)
    private PortfolioSnapshotService portfolioSnapshotService;

    @Transactional
    public ProventResponseDTO createProvent(ProventRequestDTO dto) {
        validateDates(dto);
        Asset asset = assetRepository.findById(dto.assetId())
                .orElseThrow(() -> new AssetNotFoundException("Ativo nao encontrado"));
        return createProvent(dto, asset, ProventOriginMetadata.manual(asset.getTicker(), dto));
    }

    @Transactional
    public ProventResponseDTO createProvent(ProventRequestDTO dto, ProventOriginMetadata originMetadata) {
        validateDates(dto);
        Asset asset = assetRepository.findById(dto.assetId())
                .orElseThrow(() -> new AssetNotFoundException("Ativo nao encontrado"));
        return createProvent(dto, asset, originMetadata);
    }

    @Transactional
    public ProventResponseDTO createProvent(
            ProventRequestDTO dto,
            Asset asset,
            ProventOriginMetadata originMetadata
    ) {
        validateDates(dto);
        validateOriginMetadata(originMetadata);

        boolean duplicated = proventRepository.existsByOriginSourceAndOriginEventKey(
                originMetadata.source(),
                originMetadata.sourceEventKey()
        );
        if (duplicated) {
            throw new ProventAlreadyExistsException("Ja existe um provento igual para esse evento de origem.");
        }

        Provent provent = proventMapper.toEntity(dto);
        provent.setAsset(asset);
        provent.setOriginSource(originMetadata.source());
        provent.setOriginEventKey(originMetadata.sourceEventKey());
        provent.setOriginLabel(originMetadata.sourceLabel());
        provent.setOriginRelatedTo(originMetadata.sourceRelatedTo());
        provent.setOriginAssetIssued(originMetadata.sourceAssetIssued());
        provent.setOriginIsinCode(originMetadata.sourceIsinCode());
        provent.setOriginRemarks(originMetadata.sourceRemarks());
        provent.setOriginApprovedOn(originMetadata.sourceApprovedOn());
        provent.setOriginLastDatePrior(originMetadata.sourceLastDatePrior());
        provent.setOriginRate(originMetadata.sourceRate());
        provent.setOriginRateBasis(originMetadata.sourceRateBasis());

        Provent savedProvent;
        try {
            savedProvent = proventRepository.save(provent);
        } catch (DataIntegrityViolationException exception) {
            throw new ProventAlreadyExistsException("Ja existe um provento igual para esse evento de origem.");
        }

        List<UserAssetHoldingProjection> holdings = transactionRepository.findPortfolioHoldingsByAssetAtDate(
                asset.getId(),
                marketCalendar.endOfMarketDay(dto.comDate()),
                TransactionType.BUY
        );

        Map<UUID, Portfolio> portfoliosById = portfolioRepository.findAllById(
                        holdings.stream().map(UserAssetHoldingProjection::getPortfolioId).toList()
                ).stream()
                .collect(Collectors.toMap(Portfolio::getId, portfolio -> portfolio));

        List<ProventProvision> provisions = new ArrayList<>();
        for (UserAssetHoldingProjection holding : holdings) {
            Portfolio portfolio = portfoliosById.get(holding.getPortfolioId());
            if (portfolio == null) {
                continue;
            }

            ProventProvision provision = new ProventProvision();
            populateProvisionFields(provision, savedProvent, portfolio, asset, holding.getQuantity());
            provisions.add(provision);
        }

        proventProvisionRepository.saveAll(provisions);
        provisions.forEach(provision ->
                invalidateSnapshotsFrom(provision.getPortfolio(), savedProvent.getPaymentDate()));

        return proventMapper.toResponse(savedProvent, asset, provisions.size());
    }

    /**
     * Reconciles provent provisions for a single portfolio and asset starting from {@code fromComDate}
     * (inclusive), or for all provents of the asset when {@code fromComDate} is {@code null}.
     * Intended to be called after retroactive transactions are recorded or replayed.
     */
    @Transactional
    public void reconcileProvisionsForPortfolioAndAsset(Portfolio portfolio, Asset asset, LocalDate fromComDate) {
        if (portfolio == null || portfolio.getId() == null || asset == null || asset.getId() == null) {
            return;
        }

        List<Provent> provents = fromComDate != null
                ? proventRepository.findByAssetAndComDateGreaterThanEqual(asset, fromComDate)
                : proventRepository.findByAsset(asset);

        for (Provent provent : provents) {
            BigDecimal quantity = transactionRepository.findPortfolioHoldingQuantityByAssetAtDate(
                    portfolio.getId(),
                    asset.getId(),
                    marketCalendar.endOfMarketDay(provent.getComDate()),
                    TransactionType.BUY
            );

            Optional<ProventProvision> existing = proventProvisionRepository.findByProventAndPortfolio(provent, portfolio);

            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                existing.ifPresent(provision -> {
                    proventProvisionRepository.delete(provision);
                    invalidateSnapshotsFrom(portfolio, provent.getPaymentDate());
                });
                continue;
            }

            ProventProvision provision = existing.orElseGet(ProventProvision::new);
            populateProvisionFields(provision, provent, portfolio, asset, quantity);
            proventProvisionRepository.save(provision);
            invalidateSnapshotsFrom(portfolio, provent.getPaymentDate());
        }
    }

    /**
     * Convenience overload reconciling all provents for the given portfolio and asset.
     */
    @Transactional
    public void reconcileProvisionsForPortfolioAndAsset(Portfolio portfolio, Asset asset) {
        reconcileProvisionsForPortfolioAndAsset(portfolio, asset, null);
    }

    /**
     * Reconciles provisions across all portfolios for an existing provent identified by {@code originMetadata}.
     * Ensures idempotent syncs also backfill or remove provisions after retroactive transactions.
     */
    @Transactional
    public void reconcileProvisionsForExistingProvent(ProventOriginMetadata originMetadata) {
        if (originMetadata == null || originMetadata.source() == null
                || originMetadata.sourceEventKey() == null || originMetadata.sourceEventKey().isBlank()) {
            return;
        }

        Optional<Provent> existingProvent = proventRepository.findByOriginSourceAndOriginEventKey(
                originMetadata.source(),
                originMetadata.sourceEventKey()
        );
        if (existingProvent.isEmpty()) {
            return;
        }

        Provent provent = existingProvent.get();
        Asset asset = provent.getAsset();

        List<UserAssetHoldingProjection> holdings = transactionRepository.findPortfolioHoldingsByAssetAtDate(
                asset.getId(),
                marketCalendar.endOfMarketDay(provent.getComDate()),
                TransactionType.BUY
        );

        Map<UUID, BigDecimal> quantityByPortfolioId = holdings.stream()
                .collect(Collectors.toMap(
                        UserAssetHoldingProjection::getPortfolioId,
                        UserAssetHoldingProjection::getQuantity
                ));

        Map<UUID, ProventProvision> existingByPortfolioId = proventProvisionRepository.findByProvent(provent).stream()
                .collect(Collectors.toMap(
                        pp -> pp.getPortfolio().getId(),
                        pp -> pp
                ));

        List<ProventProvision> toDelete = existingByPortfolioId.entrySet().stream()
                .filter(entry -> !quantityByPortfolioId.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (!toDelete.isEmpty()) {
            proventProvisionRepository.deleteAll(toDelete);
            toDelete.forEach(provision ->
                    invalidateSnapshotsFrom(provision.getPortfolio(), provent.getPaymentDate()));
        }

        if (quantityByPortfolioId.isEmpty()) {
            return;
        }

        Map<UUID, Portfolio> portfoliosById = portfolioRepository.findAllById(quantityByPortfolioId.keySet()).stream()
                .collect(Collectors.toMap(Portfolio::getId, p -> p));

        List<ProventProvision> toSave = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : quantityByPortfolioId.entrySet()) {
            Portfolio portfolio = portfoliosById.get(entry.getKey());
            if (portfolio == null) {
                continue;
            }
            ProventProvision provision = existingByPortfolioId.getOrDefault(entry.getKey(), new ProventProvision());
            populateProvisionFields(provision, provent, portfolio, asset, entry.getValue());
            toSave.add(provision);
        }

        if (!toSave.isEmpty()) {
            proventProvisionRepository.saveAll(toSave);
            toSave.forEach(provision ->
                    invalidateSnapshotsFrom(provision.getPortfolio(), provent.getPaymentDate()));
        }
    }

    private void invalidateSnapshotsFrom(Portfolio portfolio, LocalDate paymentDate) {
        if (portfolioSnapshotService != null && paymentDate != null) {
            portfolioSnapshotService.invalidateSnapshotsFrom(portfolio, paymentDate);
        }
    }

    @Transactional(readOnly = true)
    public Page<ProventProvisionResponseDTO> getMyProvents(User user, UUID portfolioId, Pageable pageable) {
        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, portfolioId);
        return proventProvisionRepository.findByPortfolio(portfolio, pageable)
                .map(proventMapper::toProvisionResponse);
    }

    private void populateProvisionFields(
            ProventProvision provision,
            Provent provent,
            Portfolio portfolio,
            Asset asset,
            BigDecimal quantity
    ) {
        BigDecimal scaledQuantity = quantity.setScale(FinancialConstants.QUANTITY_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        BigDecimal grossAmount = quantity.multiply(provent.getAmountPerShare())
                .setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);

        BigDecimal withholdingTaxAmount = resolveWithholdingTaxAmount(provent, grossAmount);
        BigDecimal netAmount = grossAmount.subtract(withholdingTaxAmount)
                .setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);

        provision.setProvent(provent);
        provision.setPortfolio(portfolio);
        provision.setAsset(asset);
        provision.setQuantityOnComDate(scaledQuantity);
        provision.setGrossAmount(grossAmount);
        provision.setWithholdingTaxAmount(withholdingTaxAmount);
        provision.setNetAmount(netAmount);
        provision.setStatus(resolveProvisionStatus(provent));
    }

    private BigDecimal resolveWithholdingTaxAmount(Provent provent, BigDecimal grossAmount) {
        if (provent.getType() == ProventType.JCP && provent.getOriginRateBasis() == ProventRateBasis.GROSS) {
            BigDecimal taxRate = resolveJcpWithholdingTaxRate(provent);
            return grossAmount.multiply(taxRate)
                    .setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
        }
        return BigDecimal.ZERO.setScale(FinancialConstants.EXTENDED_PRECISION_SCALE, FinancialConstants.DEFAULT_ROUNDING);
    }

    private BigDecimal resolveJcpWithholdingTaxRate(Provent provent) {
        LocalDate referenceDate = resolveJcpTaxReferenceDate(provent);
        if (referenceDate != null && referenceDate.isBefore(JCP_LC_224_2025_EFFECTIVE_DATE)) {
            return JCP_WITHHOLDING_TAX_RATE_BEFORE_2026;
        }
        return JCP_WITHHOLDING_TAX_RATE_FROM_2026;
    }

    private LocalDate resolveJcpTaxReferenceDate(Provent provent) {
        if (provent.getPaymentDate() != null) {
            return provent.getPaymentDate();
        }
        if (provent.getComDate() != null) {
            return provent.getComDate();
        }
        if (provent.getOriginLastDatePrior() != null) {
            return marketCalendar.financialDate(provent.getOriginLastDatePrior());
        }
        if (provent.getOriginApprovedOn() != null) {
            return marketCalendar.financialDate(provent.getOriginApprovedOn());
        }
        return null;
    }

    private ProventStatus resolveProvisionStatus(Provent provent) {
        LocalDate today = marketCalendar.today();
        if (today != null && provent.getPaymentDate() != null && !provent.getPaymentDate().isAfter(today)) {
            return ProventStatus.PAID;
        }
        return ProventStatus.PENDING;
    }

    private void validateDates(ProventRequestDTO dto) {
        if (dto.paymentDate().isBefore(dto.comDate())) {
            throw new IllegalArgumentException("A data de pagamento nao pode ser anterior a data COM.");
        }
    }

    private void validateOriginMetadata(ProventOriginMetadata originMetadata) {
        if (originMetadata == null) {
            throw new IllegalArgumentException("A origem do provento e obrigatoria.");
        }
        if (originMetadata.source() == null) {
            throw new IllegalArgumentException("A origem do provento e obrigatoria.");
        }
        if (originMetadata.sourceEventKey() == null || originMetadata.sourceEventKey().isBlank()) {
            throw new IllegalArgumentException("A chave de origem do provento e obrigatoria.");
        }
    }
}
