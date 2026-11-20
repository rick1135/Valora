package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioDailySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioDailySnapshotRepository extends JpaRepository<PortfolioDailySnapshot, Long> {
    List<PortfolioDailySnapshot> findByPortfolioOrderBySnapshotDateAsc(Portfolio portfolio);

    List<PortfolioDailySnapshot> findByPortfolioAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            Portfolio portfolio, LocalDate start, LocalDate end
    );

    Optional<PortfolioDailySnapshot> findFirstByPortfolioOrderBySnapshotDateDesc(Portfolio portfolio);

    @Modifying(flushAutomatically = true)
    @Query("delete from PortfolioDailySnapshot s where s.portfolio = :portfolio and s.snapshotDate >= :snapshotDate")
    void deleteByPortfolioAndSnapshotDateGreaterThanEqual(
            @Param("portfolio") Portfolio portfolio, @Param("snapshotDate") LocalDate snapshotDate
    );

    @Query(value = "select id from portfolios where id = :portfolioId for no key update", nativeQuery = true)
    Optional<UUID> lockPortfolio(@Param("portfolioId") UUID portfolioId);
}
