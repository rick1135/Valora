package com.rick1135.Valora.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "benchmark_values")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class BenchmarkValue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benchmark_id", nullable = false)
    private Benchmark benchmark;

    @Column(name = "reference_date", nullable = false)
    private LocalDate referenceDate;

    @Column(nullable = false, precision = 19, scale = 10)
    private BigDecimal value;
}
