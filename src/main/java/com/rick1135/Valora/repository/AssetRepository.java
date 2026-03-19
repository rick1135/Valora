package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {
    List<Asset> findByTickerContainingIgnoreCase(String ticker);
    Optional<Asset> findByTickerIgnoreCase(String ticker);
}
