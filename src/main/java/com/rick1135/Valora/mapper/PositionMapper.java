package com.rick1135.Valora.mapper;

import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.entity.Position;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PositionMapper {
    @Mapping(target = "assetId", source = "position.asset.id")
    @Mapping(target = "ticker", source = "position.asset.ticker")
    @Mapping(target = "assetName", source = "position.asset.name")
    @Mapping(target = "quantity", source = "position.quantity")
    @Mapping(target = "averagePrice", source = "position.averagePrice")
    @Mapping(target = "totalCost", source = "totalCost")
    @Mapping(target = "currentPrice", source = "currentPrice")
    @Mapping(target = "currentTotalValue", source = "currentTotalValue")
    @Mapping(target = "profitability", source = "profitability")
    PositionResponseDTO toResponse(
            Position position,
            BigDecimal totalCost,
            BigDecimal currentPrice,
            BigDecimal currentTotalValue,
            BigDecimal profitability
    );
}
