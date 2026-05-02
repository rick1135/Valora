package com.rick1135.Valora.mapper;

import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.dto.response.ProventProvisionResponseDTO;
import com.rick1135.Valora.dto.response.ProventResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.Provent;
import com.rick1135.Valora.entity.ProventProvision;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProventMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "asset", ignore = true)
    @Mapping(target = "originSource", ignore = true)
    @Mapping(target = "originEventKey", ignore = true)
    @Mapping(target = "originLabel", ignore = true)
    @Mapping(target = "originRelatedTo", ignore = true)
    @Mapping(target = "originAssetIssued", ignore = true)
    @Mapping(target = "originIsinCode", ignore = true)
    @Mapping(target = "originRemarks", ignore = true)
    @Mapping(target = "originApprovedOn", ignore = true)
    @Mapping(target = "originLastDatePrior", ignore = true)
    @Mapping(target = "originRate", ignore = true)
    @Mapping(target = "originRateBasis", ignore = true)
    Provent toEntity(ProventRequestDTO dto);

    @Mapping(target = "proventId", source = "provent.id")
    @Mapping(target = "assetId", source = "asset.id")
    @Mapping(target = "ticker", source = "asset.ticker")
    @Mapping(target = "type", source = "provent.type")
    @Mapping(target = "amountPerShare", source = "provent.amountPerShare")
    @Mapping(target = "comDate", source = "provent.comDate")
    @Mapping(target = "paymentDate", source = "provent.paymentDate")
    @Mapping(target = "provisionedUsers", source = "provisionedUsers")
    @Mapping(target = "originSource", source = "provent.originSource")
    @Mapping(target = "originEventKey", source = "provent.originEventKey")
    @Mapping(target = "originRateBasis", source = "provent.originRateBasis")
    ProventResponseDTO toResponse(Provent provent, Asset asset, int provisionedUsers);

    @Mapping(target = "provisionId", source = "provision.id")
    @Mapping(target = "proventId", source = "provision.provent.id")
    @Mapping(target = "assetId", source = "provision.asset.id")
    @Mapping(target = "ticker", source = "provision.asset.ticker")
    @Mapping(target = "type", source = "provision.provent.type")
    @Mapping(target = "amountPerShare", source = "provision.provent.amountPerShare")
    @Mapping(target = "comDate", source = "provision.provent.comDate")
    @Mapping(target = "paymentDate", source = "provision.provent.paymentDate")
    @Mapping(target = "quantityOnComDate", source = "provision.quantityOnComDate")
    @Mapping(target = "grossAmount", source = "provision.grossAmount")
    @Mapping(target = "withholdingTaxAmount", source = "provision.withholdingTaxAmount")
    @Mapping(target = "netAmount", source = "provision.netAmount")
    @Mapping(target = "status", source = "provision.status")
    @Mapping(target = "originSource", source = "provision.provent.originSource")
    @Mapping(target = "originEventKey", source = "provision.provent.originEventKey")
    @Mapping(target = "originRateBasis", source = "provision.provent.originRateBasis")
    ProventProvisionResponseDTO toProvisionResponse(ProventProvision provision);
}
