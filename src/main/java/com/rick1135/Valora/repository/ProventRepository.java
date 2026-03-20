package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Provent;
import com.rick1135.Valora.entity.ProventSource;
import com.rick1135.Valora.entity.ProventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface ProventRepository extends JpaRepository<Provent, UUID> {
    boolean existsByOriginSourceAndOriginEventKey(ProventSource originSource, String originEventKey);

    boolean existsByAssetIdAndTypeAndComDateAndPaymentDate(
            UUID assetId,
            ProventType type,
            Instant comDate,
            Instant paymentDate
    );
}
