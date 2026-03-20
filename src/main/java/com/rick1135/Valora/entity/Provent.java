package com.rick1135.Valora.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Provent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull(message = "O ativo e obrigatorio")
    @ManyToOne(optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @NotNull(message = "O tipo de provento e obrigatorio")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProventType type;

    @NotNull(message = "O valor por cota e obrigatorio")
    @DecimalMin(value = "0.00000001", message = "O valor por cota deve ser maior que zero")
    @Column(name = "amount_per_share", nullable = false, precision = 19, scale = 8)
    private BigDecimal amountPerShare;

    @NotNull(message = "A origem do provento e obrigatoria")
    @Enumerated(EnumType.STRING)
    @Column(name = "origin_source", nullable = false, length = 20)
    private ProventSource originSource;

    @NotNull(message = "A chave de origem do provento e obrigatoria")
    @Column(name = "origin_event_key", nullable = false, length = 1024)
    private String originEventKey;

    @Column(name = "origin_label", length = 100)
    private String originLabel;

    @Column(name = "origin_related_to", length = 100)
    private String originRelatedTo;

    @Column(name = "origin_asset_issued", length = 50)
    private String originAssetIssued;

    @Column(name = "origin_isin_code", length = 20)
    private String originIsinCode;

    @Column(name = "origin_remarks", length = 255)
    private String originRemarks;

    @Column(name = "origin_approved_on")
    private Instant originApprovedOn;

    @Column(name = "origin_last_date_prior")
    private Instant originLastDatePrior;

    @Column(name = "origin_rate", precision = 19, scale = 8)
    private BigDecimal originRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_rate_basis", length = 20)
    private ProventRateBasis originRateBasis;

    @NotNull(message = "A data COM e obrigatoria")
    @Column(name = "com_date", nullable = false)
    private Instant comDate;

    @NotNull(message = "A data de pagamento e obrigatoria")
    @Column(name = "payment_date", nullable = false)
    private Instant paymentDate;
}
