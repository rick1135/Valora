package com.rick1135.Valora.repository;

import com.rick1135.Valora.entity.Benchmark;
import com.rick1135.Valora.entity.BenchmarkValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BenchmarkValueRepository extends JpaRepository<BenchmarkValue, Long> {
    Optional<BenchmarkValue> findByBenchmarkAndReferenceDate(Benchmark benchmark, LocalDate referenceDate);

    @Query("SELECT bv FROM BenchmarkValue bv WHERE bv.benchmark = :benchmark AND bv.referenceDate BETWEEN :start AND :end ORDER BY bv.referenceDate ASC")
    List<BenchmarkValue> findByBenchmarkInRange(Benchmark benchmark, LocalDate start, LocalDate end);

    @Query("SELECT bv FROM BenchmarkValue bv WHERE bv.benchmark = :benchmark AND bv.referenceDate <= :date ORDER BY bv.referenceDate DESC LIMIT 1")
    Optional<BenchmarkValue> findLatestBeforeOrAt(Benchmark benchmark, LocalDate date);

    @Query("SELECT bv FROM BenchmarkValue bv WHERE bv.benchmark = :benchmark ORDER BY bv.referenceDate DESC LIMIT 1")
    Optional<BenchmarkValue> findLatestByBenchmark(Benchmark benchmark);

    @Query("SELECT bv FROM BenchmarkValue bv WHERE bv.benchmark = :benchmark ORDER BY bv.referenceDate ASC LIMIT 1")
    Optional<BenchmarkValue> findEarliestByBenchmark(Benchmark benchmark);

    long countByBenchmark(Benchmark benchmark);

    @Query("SELECT bv.referenceDate FROM BenchmarkValue bv WHERE bv.benchmark = :benchmark AND bv.referenceDate BETWEEN :start AND :end ORDER BY bv.referenceDate ASC")
    List<LocalDate> findReferenceDatesByBenchmarkInRange(Benchmark benchmark, LocalDate start, LocalDate end);

    @Modifying
    @Query(value = """
            INSERT INTO benchmark_values (benchmark_id, reference_date, value)
            VALUES (:benchmarkId, :referenceDate, :value)
            ON CONFLICT (benchmark_id, reference_date) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(UUID benchmarkId, LocalDate referenceDate, BigDecimal value);
}
