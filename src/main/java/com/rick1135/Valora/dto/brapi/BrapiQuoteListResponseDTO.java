package com.rick1135.Valora.dto.brapi;

import java.util.List;

public record BrapiQuoteListResponseDTO(
        List<BrapiListedAssetDTO> stocks,
        Integer currentPage,
        Integer totalPages,
        Boolean hasNextPage
) {
}
