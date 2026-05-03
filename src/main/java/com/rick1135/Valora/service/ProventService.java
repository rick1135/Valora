package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.dto.response.ProventProvisionResponseDTO;
import com.rick1135.Valora.dto.response.ProventResponseDTO;
import com.rick1135.Valora.entity.*;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.exception.ProventAlreadyExistsException;
import com.rick1135.Valora.mapper.ProventMapper;
import com.rick1135.Valora.repository.*;
import com.rick1135.Valora.repository.projection.UserAssetHoldingProjection;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProventService {
    private final AssetRepository assetRepository;
    private final ProventRepository proventRepository;
    private final ProventProvisionRepository proventProvisionRepository;
    private final TransactionRepository transactionRepository;
    private final PortfolioRepository portfolioRepository;
    private final PortfolioService portfolioService;
    private final ProventMapper proventMapper;
    private final MarketCalendar marketCalendar;

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

            BigDecimal quantity = holding.getQuantity();
            BigDecimal grossAmount = quantity.multiply(savedProvent.getAmountPerShare()).setScale(com.rick1135.Valora.common.FinancialConstants.EXTENDED_PRECISION_SCALE, com.rick1135.Valora.common.FinancialConstants.DEFAULT_ROUNDING);

            BigDecimal withholdingTaxAmount = BigDecimal.ZERO.setScale(com.rick1135.Valora.common.FinancialConstants.EXTENDED_PRECISION_SCALE, com.rick1135.Valora.common.FinancialConstants.DEFAULT_ROUNDING);
            BigDecimal netAmount = grossAmount.subtract(withholdingTaxAmount).setScale(com.rick1135.Valora.common.FinancialConstants.EXTENDED_PRECISION_SCALE, com.rick1135.Valora.common.FinancialConstants.DEFAULT_ROUNDING);

            ProventProvision provision = new ProventProvision();
            provision.setProvent(savedProvent);
            provision.setPortfolio(portfolio);
            provision.setAsset(asset);
            provision.setQuantityOnComDate(quantity.setScale(com.rick1135.Valora.common.FinancialConstants.QUANTITY_SCALE, com.rick1135.Valora.common.FinancialConstants.DEFAULT_ROUNDING));
            provision.setGrossAmount(grossAmount);
            provision.setWithholdingTaxAmount(withholdingTaxAmount);
            provision.setNetAmount(netAmount);
            provision.setStatus(ProventStatus.PENDING);
            provisions.add(provision);
        }

        proventProvisionRepository.saveAll(provisions);

        return proventMapper.toResponse(savedProvent, asset, provisions.size());
    }

    @Transactional(readOnly = true)
    public Page<ProventProvisionResponseDTO> getMyProvents(User user, UUID portfolioId, Pageable pageable) {
        Portfolio portfolio = portfolioService.resolveOwnedPortfolio(user, portfolioId);
        return proventProvisionRepository.findByPortfolio(portfolio, pageable)
                .map(proventMapper::toProvisionResponse);
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
