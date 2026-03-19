package com.rick1135.Valora.entity;

import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "ticker_normalized", unique = true, nullable = false, length = 20)
    private String tickerNormalized;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false,   length = 50)
    private AssetCategory category;

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
