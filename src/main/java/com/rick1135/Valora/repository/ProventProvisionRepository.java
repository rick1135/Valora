package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.ProventProvision;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ProventProvisionRepository extends JpaRepository<ProventProvision, UUID> {
    List<ProventProvision> findByPortfolioOrderByProventPaymentDateDescProventComDateDesc(Portfolio portfolio);

    Page<ProventProvision> findByPortfolio(Portfolio portfolio, Pageable pageable);

    boolean existsByPortfolio(Portfolio portfolio);

    @Query("""
            select coalesce(sum(pp.netAmount), 0)
            from ProventProvision pp
            where pp.portfolio = :portfolio and pp.status = :status
            """)
    BigDecimal sumNetAmountByPortfolioAndStatus(
            @Param("portfolio") Portfolio portfolio,
            @Param("status") ProventStatus status
    );

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("""
            update ProventProvision pp
            set pp.status = com.rick1135.Valora.entity.ProventStatus.PAID
            where pp.status = com.rick1135.Valora.entity.ProventStatus.PENDING
            and pp.provent.paymentDate <= :now
            """)
    int updatePendingToPaid(@Param("now") java.time.Instant now);
}
