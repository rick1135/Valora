package com.rick1135.Valora.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "benchmarks")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class Benchmark {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20, unique = true)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BenchmarkType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BenchmarkProvider provider;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
