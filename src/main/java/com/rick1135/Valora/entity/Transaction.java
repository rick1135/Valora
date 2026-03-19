package com.rick1135.Valora.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(of="id")
public class Transaction {
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

    @NotNull(message = "O tipo da transacao e obrigatorio")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @NotNull(message = "A quantidade é obrigatória")
    @DecimalMin(value = "0.00000001", message = "A quantidade deve ser maior que zero")
    private BigDecimal quantity;

    @NotNull(message = "O preço unitário é obrigatório")
    @DecimalMin(value = "0.00000001", message = "O preço unitário deve ser maior que zero")
    private BigDecimal unitPrice;

    @NotNull(message = "A data da transação é obrigatória")
    private Instant transactionDate;
}
