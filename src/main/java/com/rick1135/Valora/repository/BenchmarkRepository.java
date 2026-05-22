package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BenchmarkRepository extends JpaRepository<Benchmark, UUID> {
    Optional<Benchmark> findByTicker(String ticker);
    List<Benchmark> findByProvider(BenchmarkProvider provider);
}
