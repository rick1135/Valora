package com.rick1135.Valora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "portfolio_daily_snapshots", uniqueConstraints = @UniqueConstraint(columnNames = {"portfolio_id", "snapshot_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioDailySnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "total_patrimony", nullable = false, precision = 19, scale = 8)
    private BigDecimal totalPatrimony;

    @Column(name = "total_invested", nullable = false, precision = 19, scale = 8)
    private BigDecimal totalInvested;

    @Column(name = "net_cash_flow", nullable = false, precision = 19, scale = 8)
    private BigDecimal netCashFlow;

    @Column(name = "provents_received", nullable = false, precision = 19, scale = 8)
    private BigDecimal proventsReceived;

    @Column(name = "daily_return_factor", nullable = false, precision = 19, scale = 12)
    private BigDecimal dailyReturnFactor;

    @Column(name = "twr_quota_value", nullable = false, precision = 19, scale = 12)
    private BigDecimal twrQuotaValue;

    @Column(name = "pricing_complete", nullable = false)
    private boolean pricingComplete = true;

    @Column(name = "twr_segment", nullable = false)
    private int twrSegment = 1;

    @Column(name = "recoverable_pricing_gap", nullable = false)
    private boolean recoverablePricingGap;

    public PortfolioDailySnapshot(Long id, Portfolio portfolio, LocalDate snapshotDate, BigDecimal totalPatrimony,
                                  BigDecimal totalInvested, BigDecimal netCashFlow, BigDecimal proventsReceived,
                                  BigDecimal dailyReturnFactor, BigDecimal twrQuotaValue, boolean pricingComplete) {
        this(id, portfolio, snapshotDate, totalPatrimony, totalInvested, netCashFlow, proventsReceived,
                dailyReturnFactor, twrQuotaValue, pricingComplete, 1, false);
    }
}
