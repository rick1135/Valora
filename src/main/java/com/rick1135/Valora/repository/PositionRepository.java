package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Position;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PositionRepository extends JpaRepository<Position, UUID> {
}
