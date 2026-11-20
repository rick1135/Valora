package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.request.PortfolioRequestDTO;
import com.rick1135.Valora.dto.response.PortfolioHistoryResponseDTO;
import com.rick1135.Valora.dto.response.PortfolioResponseDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.PortfolioTargetResponseDTO;
import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.dto.response.SuggestedOrderDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.service.PortfolioService;
import com.rick1135.Valora.service.PortfolioSnapshotService;
import com.rick1135.Valora.service.PositionService;
import com.rick1135.Valora.service.SmartRebalancingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/portfolios")
@RequiredArgsConstructor
public class PortfolioController {
    private final PositionService positionService;
    private final PortfolioService portfolioService;
    private final SmartRebalancingService smartRebalancingService;
    private final PortfolioSnapshotService portfolioSnapshotService;

    @PostMapping
    public ResponseEntity<PortfolioResponseDTO> createPortfolio(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody PortfolioRequestDTO dto
    ) {
        return ResponseEntity.status(201).body(portfolioService.createPortfolio(user, dto));
    }

    @GetMapping
    public ResponseEntity<List<PortfolioResponseDTO>> listPortfolios(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(portfolioService.listPortfolios(user));
    }

    @GetMapping("/{portfolioId}")
    public ResponseEntity<PortfolioResponseDTO> getPortfolio(
            @AuthenticationPrincipal User user,
            @PathVariable UUID portfolioId
    ) {
        return ResponseEntity.ok(portfolioService.getPortfolio(user, portfolioId));
    }

    @PutMapping("/{portfolioId}")
    public ResponseEntity<PortfolioResponseDTO> updatePortfolio(
            @AuthenticationPrincipal User user,
            @PathVariable UUID portfolioId,
            @Valid @RequestBody PortfolioRequestDTO dto
    ) {
        return ResponseEntity.ok(portfolioService.updatePortfolio(user, portfolioId, dto));
    }

    @DeleteMapping("/{portfolioId}")
    public ResponseEntity<Void> deletePortfolio(
            @AuthenticationPrincipal User user,
            @PathVariable UUID portfolioId
    ) {
        portfolioService.deletePortfolio(user, portfolioId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{portfolioId}/positions")
    public ResponseEntity<List<PositionResponseDTO>> getPortfolioPositions(
            @AuthenticationPrincipal User user,
            @PathVariable UUID portfolioId
    ) {
        return ResponseEntity.ok(positionService.getUserPortfolio(user, portfolioId));
    }

    @GetMapping("/{portfolioId}/summary")
    public ResponseEntity<PortfolioSummaryDTO> getPortfolioSummary(
            @AuthenticationPrincipal User user,
            @PathVariable UUID portfolioId
    ) {
        return ResponseEntity.ok(portfolioService.getPortfolioSummary(user, portfolioId));
    }

    @GetMapping("/{portfolioId}/history")
    public ResponseEntity<PortfolioHistoryResponseDTO> getPortfolioHistory(
            @AuthenticationPrincipal User user,
            @PathVariable UUID portfolioId,
            @RequestParam(defaultValue = "ALL") String range
    ) {
        return ResponseEntity.ok(portfolioSnapshotService.getPortfolioHistory(user, portfolioId, range));
    }

    @GetMapping("/{portfolioId}/targets")
    public ResponseEntity<List<PortfolioTargetResponseDTO>> getPortfolioTargets(
            @AuthenticationPrincipal User user,
            @PathVariable UUID portfolioId
    ) {
        return ResponseEntity.ok(smartRebalancingService.getTargets(user, portfolioId));
    }

    @PutMapping("/{portfolioId}/targets")
    public ResponseEntity<Void> savePortfolioTargets(
            @AuthenticationPrincipal User user,
            @PathVariable UUID portfolioId,
            @Valid @RequestBody List<SmartRebalancingService.PortfolioTargetInput> targets
    ) {
        smartRebalancingService.saveTargets(user, portfolioId, targets);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{portfolioId}/rebalance")
    public ResponseEntity<List<SuggestedOrderDTO>> calculateRebalance(
            @AuthenticationPrincipal User user,
            @PathVariable UUID portfolioId,
            @RequestParam(defaultValue = "0") BigDecimal contributionAmount
    ) {
        if (contributionAmount == null || contributionAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("O valor do aporte nao pode ser negativo.");
        }
        return ResponseEntity.ok(smartRebalancingService.calculateSuggestedOrders(user, portfolioId, contributionAmount));
    }
}
