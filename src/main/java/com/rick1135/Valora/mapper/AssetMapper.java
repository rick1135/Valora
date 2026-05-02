package com.rick1135.Valora.mapper;

import com.rick1135.Valora.dto.request.AssetRequestDTO;
import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.Asset;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AssetMapper {
    Asset toEntity(AssetRequestDTO dto);

    AssetResponseDTO toResponse(Asset asset);
}
