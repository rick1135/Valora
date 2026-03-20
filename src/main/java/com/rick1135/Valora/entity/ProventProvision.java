package com.rick1135.Valora.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "provent_provisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class ProventProvision {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull(message = "O provento é obrigatorio")
    @ManyToOne(optional = false)
    @JoinColumn(name = "provent_id", nullable = false)
    private Provent provent;

    @NotNull(message = "O usuario é obrigatorio")
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotNull(message = "O ativo é obrigatorio")
    @ManyToOne(optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @NotNull(message = "A quantidade na data COM é obrigatoria")
    @DecimalMin(value = "0.00000000", message = "A quantidade na data COM nao pode ser negativa")
    @Column(name = "quantity_on_com_date", nullable = false, precision = 19, scale = 8)
    private BigDecimal quantityOnComDate;

    @NotNull(message = "O valor bruto provisionado é obrigatorio")
    @DecimalMin(value = "0.00000000", message = "O valor bruto nao pode ser negativo")
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 8)
    private BigDecimal grossAmount;

    @NotNull(message = "O valor de imposto retido é obrigatorio")
    @DecimalMin(value = "0.00000000", message = "O imposto retido nao pode ser negativo")
    @Column(name = "withholding_tax_amount", nullable = false, precision = 19, scale = 8)
    private BigDecimal withholdingTaxAmount;

    @NotNull(message = "O valor liquido provisionado e obrigatorio")
    @DecimalMin(value = "0.00000000", message = "O valor liquido nao pode ser negativo")
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 8)
    private BigDecimal netAmount;

    @NotNull(message = "O status do provento e obrigatorio")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProventStatus status;
}
