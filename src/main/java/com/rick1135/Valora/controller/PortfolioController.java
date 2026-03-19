package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.service.PositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/portfolio")
@RequiredArgsConstructor
public class PortfolioController {
    private final PositionService positionService;

    @GetMapping
    public ResponseEntity<List<PositionResponseDTO>> getPortfolio(@AuthenticationPrincipal User user) {
        List<PositionResponseDTO> portfolio = positionService.getUserPortfolio(user);
        return ResponseEntity.ok(portfolio);
    }
}
