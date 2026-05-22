package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.PortfolioTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PortfolioTargetRepository extends JpaRepository<PortfolioTarget, UUID> {
    List<PortfolioTarget> findByPortfolio(Portfolio portfolio);
    void deleteByPortfolio(Portfolio portfolio);
}
