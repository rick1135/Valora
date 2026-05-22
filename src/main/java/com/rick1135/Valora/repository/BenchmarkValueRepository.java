package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BenchmarkValueRepository extends JpaRepository<BenchmarkValue, Long> {
    Optional<BenchmarkValue> findByBenchmarkAndReferenceDate(Benchmark benchmark, LocalDate referenceDate);

    @Query("SELECT bv FROM BenchmarkValue bv WHERE bv.benchmark = :benchmark AND bv.referenceDate BETWEEN :start AND :end ORDER BY bv.referenceDate ASC")
    List<BenchmarkValue> findByBenchmarkInRange(Benchmark benchmark, LocalDate start, LocalDate end);

    @Query("SELECT bv FROM BenchmarkValue bv WHERE bv.benchmark = :benchmark AND bv.referenceDate <= :date ORDER BY bv.referenceDate DESC LIMIT 1")
    Optional<BenchmarkValue> findLatestBeforeOrAt(Benchmark benchmark, LocalDate date);

    @Query("SELECT bv FROM BenchmarkValue bv WHERE bv.benchmark = :benchmark ORDER BY bv.referenceDate DESC LIMIT 1")
    Optional<BenchmarkValue> findLatestByBenchmark(Benchmark benchmark);
}
