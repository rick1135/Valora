package com.rick1135.Valora.mapper;

import com.rick1135.Valora.dto.request.TransactionDTO;
import com.rick1135.Valora.dto.response.TransactionResponseDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.Position;
import com.rick1135.Valora.entity.Transaction;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TransactionMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "asset", ignore = true)
    Transaction toEntity(TransactionDTO dto);

    @Mapping(target = "transactionId", source = "transaction.id")
    @Mapping(target = "assetId", source = "asset.id")
    @Mapping(target = "ticker", source = "asset.ticker")
    @Mapping(target = "type", source = "transaction.type")
    @Mapping(target = "quantity", source = "transaction.quantity")
    @Mapping(target = "unitPrice", source = "transaction.unitPrice")
    @Mapping(target = "transactionDate", source = "transaction.transactionDate")
    @Mapping(target = "positionQuantity", source = "position.quantity")
    @Mapping(target = "averagePrice", source = "position.averagePrice")
    TransactionResponseDTO toResponse(Transaction transaction, Asset asset, Position position);

    @Mapping(target = "transactionId", source = "id")
    @Mapping(target = "assetId", source = "asset.id")
    @Mapping(target = "ticker", source = "asset.ticker")
    @Mapping(target = "type", source = "type")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "unitPrice")
    @Mapping(target = "transactionDate", source = "transactionDate")
    @Mapping(target = "positionQuantity", ignore = true)
    @Mapping(target = "averagePrice", ignore = true)
    TransactionResponseDTO toHistoryResponse(Transaction transaction);
}
