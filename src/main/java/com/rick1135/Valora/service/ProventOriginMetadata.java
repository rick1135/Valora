package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.brapi.BrapiCashDividendDTO;
import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.entity.ProventRateBasis;
import com.rick1135.Valora.entity.ProventSource;
import com.rick1135.Valora.entity.ProventType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;

public record ProventOriginMetadata(
        ProventSource source,
        String sourceEventKey,
        String sourceLabel,
        String sourceRelatedTo,
        String sourceAssetIssued,
        String sourceIsinCode,
        String sourceRemarks,
        Instant sourceApprovedOn,
        Instant sourceLastDatePrior,
        BigDecimal sourceRate,
        ProventRateBasis sourceRateBasis
) {
    public static ProventOriginMetadata manual(String ticker, ProventRequestDTO dto) {
        String canonical = String.join("|",
                "MANUAL",
                normalize(ticker),
                dto.type().name(),
                dto.comDate().toString(),
                dto.paymentDate().toString(),
                scale(dto.amountPerShare())
        );

        return new ProventOriginMetadata(
                ProventSource.MANUAL,
                hash(canonical),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                dto.amountPerShare().setScale(com.rick1135.Valora.common.FinancialConstants.EXTENDED_PRECISION_SCALE, com.rick1135.Valora.common.FinancialConstants.DEFAULT_ROUNDING),
                ProventRateBasis.NET
        );
    }

    public static ProventOriginMetadata brapi(
            String ticker,
            ProventType type,
            BrapiCashDividendDTO event,
            boolean sourceRateIsGross
    ) {
        String canonical = String.join("|",
                "BRAPI",
                normalize(ticker),
                type.name(),
                value(event.assetIssued()),
                value(event.relatedTo()),
                value(event.approvedOn()),
                value(event.lastDatePrior()),
                value(event.paymentDate()),
                scale(event.rate()),
                value(event.label()),
                value(event.isinCode()),
                value(event.remarks()),
                sourceRateIsGross ? "GROSS" : "NET"
        );

        return new ProventOriginMetadata(
                ProventSource.BRAPI,
                hash(canonical),
                event.label(),
                event.relatedTo(),
                event.assetIssued(),
                event.isinCode(),
                event.remarks(),
                event.approvedOn(),
                event.lastDatePrior(),
                event.rate().setScale(com.rick1135.Valora.common.FinancialConstants.EXTENDED_PRECISION_SCALE, com.rick1135.Valora.common.FinancialConstants.DEFAULT_ROUNDING),
                sourceRateIsGross ? ProventRateBasis.GROSS : ProventRateBasis.NET
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String scale(BigDecimal value) {
        return value == null ? "" : value.setScale(com.rick1135.Valora.common.FinancialConstants.EXTENDED_PRECISION_SCALE, com.rick1135.Valora.common.FinancialConstants.DEFAULT_ROUNDING).toPlainString();
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String hash(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Nao foi possivel gerar a chave de origem do provento.", exception);
        }
    }
}
