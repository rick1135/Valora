package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.ProventProvision;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ProventProvisionRepository extends JpaRepository<ProventProvision, UUID> {
    List<ProventProvision> findByUserOrderByProventPaymentDateDescProventComDateDesc(User user);

    Page<ProventProvision> findByUser(User user, Pageable pageable);

    @Query("""
            select coalesce(sum(pp.netAmount), 0)
            from ProventProvision pp
            where pp.user = :user and pp.status = :status
            """)
    BigDecimal sumNetAmountByUserAndStatus(@Param("user") User user, @Param("status") ProventStatus status);
}
