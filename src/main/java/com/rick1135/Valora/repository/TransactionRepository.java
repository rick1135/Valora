package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.Transaction;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.repository.projection.UserAssetHoldingProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    @Query("select distinct t.asset from Transaction t")
    List<com.rick1135.Valora.entity.Asset> findDistinctAssetsWithTransactions();

    @Query("select distinct t.asset from Transaction t where t.transactionDate >= :since")
    List<com.rick1135.Valora.entity.Asset> findDistinctAssetsWithTransactionsSince(
            @Param("since") Instant since
    );

    @Query("""
           select t.portfolio.id as portfolioId,
                  sum(case when t.type = :buyType then t.quantity else -t.quantity end) as quantity
           from Transaction t
           where t.asset.id = :assetId
             and t.transactionDate <= :comDate
           group by t.portfolio.id
           having sum(case when t.type = :buyType then t.quantity else -t.quantity end) > 0
           """)
    List<UserAssetHoldingProjection> findPortfolioHoldingsByAssetAtDate(
            @Param("assetId") UUID assetId,
            @Param("comDate") Instant comDate,
            @Param("buyType") TransactionType buyType
    );

    @Query("""
           select t
           from Transaction t
           where t.portfolio = :portfolio
             and (:ticker is null or t.asset.ticker = :ticker)
             and (:type is null or t.type = :type)
             and (:startDate is null or t.transactionDate >= :startDate)
             and (:endDate is null or t.transactionDate <= :endDate)
           """)
    Page<Transaction> findTransactionHistoryByPortfolioAndFilters(
            @Param("portfolio") Portfolio portfolio,
            @Param("ticker") String ticker,
            @Param("type") TransactionType type,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate,
            Pageable pageable
    );

    boolean existsByPortfolio(Portfolio portfolio);
}
