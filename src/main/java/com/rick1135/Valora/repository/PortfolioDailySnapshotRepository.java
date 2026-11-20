package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioDailySnapshot;
import com.rick1135.Valora.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
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

    Optional<PortfolioDailySnapshot> findFirstByPortfolioAndSnapshotDateLessThanOrderBySnapshotDateDesc(
            Portfolio portfolio, LocalDate date
    );

    Optional<PortfolioDailySnapshot> findFirstByPortfolioAndPricingCompleteFalseOrderBySnapshotDateAsc(Portfolio portfolio);

    boolean existsByPortfolioAndPricingCompleteFalse(Portfolio portfolio);

    // Scalar projections keep capture revalidation independent of the persistence context's entity cache.
    @Query("""
            select t.id as id, t.transactionDate as transactionDate, t.type as type,
                   t.quantity as quantity, t.unitPrice as unitPrice,
                   a.id as assetId, a.ticker as ticker, a.category as category
            from Transaction t join t.asset a
            where t.portfolio = :portfolio and t.transactionDate < :until
            """)
    List<SnapshotTrade> findSnapshotTrades(@Param("portfolio") Portfolio portfolio, @Param("until") Instant until);

    @Query("""
            select p.paymentDate as paymentDate, sum(pp.netAmount) as netAmount
            from ProventProvision pp join pp.provent p
            where pp.portfolio = :portfolio
              and pp.status = com.rick1135.Valora.entity.ProventStatus.PAID
              and p.paymentDate between :start and :end
            group by p.paymentDate
            """)
    List<PaidProventTotal> findSnapshotPaidProvents(
            @Param("portfolio") Portfolio portfolio, @Param("start") LocalDate start, @Param("end") LocalDate end
    );

    interface SnapshotTrade {
        UUID getId();
        Instant getTransactionDate();
        TransactionType getType();
        BigDecimal getQuantity();
        BigDecimal getUnitPrice();
        UUID getAssetId();
        String getTicker();
        AssetCategory getCategory();
    }

    interface PaidProventTotal {
        LocalDate getPaymentDate();
        BigDecimal getNetAmount();
    }

    @Modifying(flushAutomatically = true)
    @Query("delete from PortfolioDailySnapshot s where s.portfolio = :portfolio and s.snapshotDate >= :snapshotDate")
    void deleteByPortfolioAndSnapshotDateGreaterThanEqual(
            @Param("portfolio") Portfolio portfolio, @Param("snapshotDate") LocalDate snapshotDate
    );

    @Query(value = "select id from portfolios where id = :portfolioId for no key update", nativeQuery = true)
    Optional<UUID> lockPortfolio(@Param("portfolioId") UUID portfolioId);
}
