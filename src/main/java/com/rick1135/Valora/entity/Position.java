package com.rick1135.Valora.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "positions")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(of="id")
public class Position {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull(message = "O usuário é obrigatório")
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotNull(message = "O ativo é obrigatório")
    @ManyToOne(optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @NotNull(message = "A quantidade é obrigatória")
    @DecimalMin(value = "0.0", message = "A quantidade total não pode ser negativa")
    private BigDecimal quantity;

    @NotNull(message = "O preço médio é obrigatório")
    @DecimalMin(value = "0.0", message = "O preço médio não pode ser negativo")
    private BigDecimal averagePrice;

    @Version
    private Long version;
}
