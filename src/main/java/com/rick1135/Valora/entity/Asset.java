package com.rick1135.Valora.entity;

import com.rick1135.Valora.common.FinancialConstants;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.*;

@Entity
@Table(name = "assets")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(of="id")
public class Asset {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String ticker;

    @Column(name = "ticker_normalized", nullable = false, length = 20)
    private String tickerNormalized;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false,   length = 50)
    private AssetCategory category;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private FixedIncomeIndexer indexer;

    @Column(name = "annual_rate", precision = 19, scale = FinancialConstants.PERCENTAGE_SCALE)
    private BigDecimal annualRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", length = 30)
    private FixedIncomeRateType rateType;

    @Column(name = "spread_rate", precision = 19, scale = FinancialConstants.PERCENTAGE_SCALE)
    private BigDecimal spreadRate;

    @Column(length = 255)
    private String issuer;

    @Column(name = "expiration_date")
    private java.time.LocalDate expirationDate;

    @PrePersist
    @PreUpdate
    private void normalizeFields() {
        if (ticker != null) {
            ticker = ticker.trim().toUpperCase(Locale.ROOT);
            tickerNormalized = ticker;
        }
        if (name != null) {
            name = name.trim();
        }
    }
}
