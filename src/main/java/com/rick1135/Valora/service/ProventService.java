package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.dto.response.ProventProvisionResponseDTO;
import com.rick1135.Valora.dto.response.ProventResponseDTO;
import com.rick1135.Valora.entity.*;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.exception.ProventAlreadyExistsException;
import com.rick1135.Valora.repository.*;
import com.rick1135.Valora.repository.projection.UserAssetHoldingProjection;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProventService {
    private final AssetRepository assetRepository;
    private final ProventRepository proventRepository;
    private final ProventProvisionRepository proventProvisionRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

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

        Provent provent = new Provent();
        provent.setAsset(asset);
        provent.setType(dto.type());
        provent.setAmountPerShare(dto.amountPerShare());
        provent.setComDate(dto.comDate());
        provent.setPaymentDate(dto.paymentDate());
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

        List<UserAssetHoldingProjection> holdings = transactionRepository.findUserHoldingsByAssetAtDate(
                asset.getId(),
                dto.comDate(),
                TransactionType.BUY
        );

        Map<UUID, User> usersById = userRepository.findAllById(
                        holdings.stream().map(UserAssetHoldingProjection::getUserId).toList()
                ).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        List<ProventProvision> provisions = new ArrayList<>();
        for (UserAssetHoldingProjection holding : holdings) {
            User user = usersById.get(holding.getUserId());
            if (user == null) {
                continue;
            }

            BigDecimal quantity = holding.getQuantity();
            BigDecimal grossAmount = quantity.multiply(savedProvent.getAmountPerShare()).setScale(8, RoundingMode.HALF_UP);

            BigDecimal withholdingTaxAmount = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
            BigDecimal netAmount = grossAmount.subtract(withholdingTaxAmount).setScale(8, RoundingMode.HALF_UP);

            ProventProvision provision = new ProventProvision();
            provision.setProvent(savedProvent);
            provision.setUser(user);
            provision.setAsset(asset);
            provision.setQuantityOnComDate(quantity.setScale(8, RoundingMode.HALF_UP));
            provision.setGrossAmount(grossAmount);
            provision.setWithholdingTaxAmount(withholdingTaxAmount);
            provision.setNetAmount(netAmount);
            provision.setStatus(ProventStatus.PENDING);
            provisions.add(provision);
        }

        proventProvisionRepository.saveAll(provisions);

        return new ProventResponseDTO(
                savedProvent.getId(),
                asset.getId(),
                asset.getTicker(),
                savedProvent.getType(),
                savedProvent.getAmountPerShare(),
                savedProvent.getComDate(),
                savedProvent.getPaymentDate(),
                provisions.size(),
                savedProvent.getOriginSource(),
                savedProvent.getOriginEventKey(),
                savedProvent.getOriginRateBasis()
        );
    }

    public Page<ProventProvisionResponseDTO> getMyProvents(User user, Pageable pageable) {
        return proventProvisionRepository.findByUser(user, pageable)
                .map(this::toResponse);
    }

    private ProventProvisionResponseDTO toResponse(ProventProvision provision) {
        Provent provent = provision.getProvent();
        return new ProventProvisionResponseDTO(
                provision.getId(),
                provent.getId(),
                provision.getAsset().getId(),
                provision.getAsset().getTicker(),
                provent.getType(),
                provent.getAmountPerShare(),
                provent.getComDate(),
                provent.getPaymentDate(),
                provision.getQuantityOnComDate(),
                provision.getGrossAmount(),
                provision.getWithholdingTaxAmount(),
                provision.getNetAmount(),
                provision.getStatus(),
                provent.getOriginSource(),
                provent.getOriginEventKey(),
                provent.getOriginRateBasis()
        );
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
