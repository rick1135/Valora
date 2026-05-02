package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {
    List<Portfolio> findByUserOrderByCreatedAtAsc(User user);

    Optional<Portfolio> findByIdAndUser(UUID id, User user);

    boolean existsByUserAndNameIgnoreCase(User user, String name);
}
