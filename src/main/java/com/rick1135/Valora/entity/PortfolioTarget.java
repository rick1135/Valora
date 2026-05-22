package com.rick1135.Valora.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "portfolio_targets")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class PortfolioTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_category", nullable = false, length = 20)
    private AssetCategory assetCategory;

    @Column(name = "target_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal targetPercentage;
}
