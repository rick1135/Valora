package com.rick1135.Valora.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

public interface UserAssetHoldingProjection {
    UUID getPortfolioId();
    BigDecimal getQuantity();
}
