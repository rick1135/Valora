package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PositionRepository extends JpaRepository<Position, UUID> {
    Optional<Position> findByPortfolioAndAsset(Portfolio portfolio, Asset asset);
    List<Position> findByPortfolio(Portfolio portfolio);

    boolean existsByPortfolio(Portfolio portfolio);

    @Query("select distinct p.asset from Position p where p.quantity > :minimum")
    List<Asset> findDistinctAssetsByQuantityGreaterThan(@Param("minimum") BigDecimal minimum);
}
