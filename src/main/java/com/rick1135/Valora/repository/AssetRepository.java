package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {
    List<Asset> findByTickerContainingIgnoreCase(String ticker);
    Optional<Asset> findByTickerIgnoreCase(String ticker);

    Optional<Asset> findByTickerIgnoreCaseAndOwnerUserIsNull(String ticker);
    Optional<Asset> findByTickerIgnoreCaseAndOwnerUser(String ticker, User ownerUser);

    long countByOwnerUserIsNull();

    @Query("SELECT a.tickerNormalized FROM Asset a WHERE a.ownerUser IS NULL")
    Set<String> findAllGlobalTickers();

    @Query("SELECT a FROM Asset a WHERE a.ownerUser IS NULL OR a.ownerUser = :user")
    Page<Asset> findVisibleToUser(@Param("user") User user, Pageable pageable);

    @Query("""
            SELECT a FROM Asset a
            WHERE (a.ownerUser IS NULL OR a.ownerUser = :user)
                AND (UPPER(a.ticker) LIKE UPPER(CONCAT('%', :ticker, '%'))
                    OR UPPER(a.name) LIKE UPPER(CONCAT('%', :ticker, '%')))
            ORDER BY CASE
                WHEN UPPER(a.ticker) = UPPER(:ticker) THEN 0
                WHEN UPPER(a.ticker) LIKE UPPER(CONCAT(:ticker, '%')) THEN 1
                ELSE 2
            END, a.ticker ASC
            """)
    List<Asset> searchVisibleToUser(@Param("user") User user, @Param("ticker") String ticker);

    @Query("""
            SELECT a FROM Asset a
            WHERE UPPER(a.ticker) = UPPER(:ticker) AND (a.ownerUser = :user OR a.ownerUser IS NULL)
            ORDER BY CASE WHEN a.ownerUser IS NOT NULL THEN 0 ELSE 1 END
            """)
    List<Asset> findVisibleByTickerIgnoreCase(@Param("user") User user, @Param("ticker") String ticker);
}
