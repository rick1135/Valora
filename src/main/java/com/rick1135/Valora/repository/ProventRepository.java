package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.Provent;
import com.rick1135.Valora.entity.ProventSource;
import com.rick1135.Valora.entity.ProventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProventRepository extends JpaRepository<Provent, UUID> {
    boolean existsByOriginSourceAndOriginEventKey(ProventSource originSource, String originEventKey);

    Optional<Provent> findByOriginSourceAndOriginEventKey(ProventSource originSource, String originEventKey);

    List<Provent> findByAsset(Asset asset);

    List<Provent> findByAssetAndComDateGreaterThanEqual(Asset asset, LocalDate comDate);

    boolean existsByAssetIdAndTypeAndComDateAndPaymentDate(
            UUID assetId,
            ProventType type,
            LocalDate comDate,
            LocalDate paymentDate
    );
}
