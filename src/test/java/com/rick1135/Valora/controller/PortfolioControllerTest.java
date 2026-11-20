package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.AssetPositionSummaryDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.PortfolioTargetResponseDTO;
import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.dto.response.SuggestedOrderDTO;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.service.PortfolioService;
import com.rick1135.Valora.service.PositionService;
import com.rick1135.Valora.service.SmartRebalancingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PortfolioControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PositionService positionService;

    @Mock
    private PortfolioService portfolioService;

    @Mock
    private SmartRebalancingService smartRebalancingService;

    @BeforeEach
    void setUp() {
        PortfolioController controller = new PortfolioController(positionService, portfolioService, smartRebalancingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getPortfolioShouldReturnCurrentPositions() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        PositionResponseDTO position = new PositionResponseDTO(
                UUID.randomUUID(),
                "VALE3",
                "Vale",
                new BigDecimal("3.00000000"),
                new BigDecimal("60.00000000"),
                new BigDecimal("180.0000000000000000"),
                new BigDecimal("62.00000000"),
                new BigDecimal("186.0000000000000000"),
                new BigDecimal("3.3333")
        );
        when(positionService.getUserPortfolio(any(), eq(portfolioId))).thenReturn(List.of(position));

        mockMvc.perform(get("/portfolios/{portfolioId}/positions", portfolioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("VALE3"))
                .andExpect(jsonPath("$[0].totalCost").value(180.0));
    }

    @Test
    void getPortfolioSummaryShouldReturnConsolidatedSnapshot() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        PortfolioSummaryDTO summary = new PortfolioSummaryDTO(
                new BigDecimal("350.00"),
                new BigDecimal("300.00"),
                new BigDecimal("50.00"),
                new PortfolioSummaryDTO.ProfitabilityDTO(
                        new BigDecimal("100.00"),
                        new BigDecimal("33.3333"),
                        new BigDecimal("5.00"),
                        new BigDecimal("1.4493"),
                        true,
                        List.of()
                ),
                List.of(
                        new AssetAllocationDTO("ACOES", new BigDecimal("250.00"), new BigDecimal("71.4286")),
                        new AssetAllocationDTO("ETF", new BigDecimal("100.00"), new BigDecimal("28.5714"))
                ),
                true,
                List.of("ETF11")
        );
        when(portfolioService.getPortfolioSummary(any(), eq(portfolioId))).thenReturn(summary);

        mockMvc.perform(get("/portfolios/{portfolioId}/summary", portfolioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPatrimony").value(350.0))
                .andExpect(jsonPath("$.profitability.absoluteProfit").value(100.0))
                .andExpect(jsonPath("$.profitability.dayAbsoluteVariation").value(5.0))
                .andExpect(jsonPath("$.fallbackQuoteUsed").value(true))
                .andExpect(jsonPath("$.fallbackTickers[0]").value("ETF11"))
                .andExpect(jsonPath("$.allocations[0].category").value("ACOES"))
                .andExpect(jsonPath("$.allocations[0].percentage").value(71.4286));
    }

    @Test
    void getPortfolioPositionsAndSummaryShouldSerializeFixedIncomeCoverage() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        LocalDate valuedThrough = LocalDate.of(2025, 4, 22);
        BigDecimal principal = new BigDecimal("1000.00");
        BigDecimal currentValue = new BigDecimal("1001.10");
        BigDecimal profitability = new BigDecimal("0.11");
        PositionResponseDTO position = new PositionResponseDTO(
                assetId, "CDB_CDI", "CDB CDI", BigDecimal.ONE, principal, principal, currentValue, currentValue,
                profitability, valuedThrough, true
        );
        AssetPositionSummaryDTO positionSummary = new AssetPositionSummaryDTO(
                assetId, "CDB_CDI", "RENDA_FIXA", currentValue, BigDecimal.ONE, currentValue, true, valuedThrough, true
        );
        PortfolioSummaryDTO summary = new PortfolioSummaryDTO(
                currentValue, principal, BigDecimal.ZERO,
                new PortfolioSummaryDTO.ProfitabilityDTO(
                        currentValue.subtract(principal), profitability, BigDecimal.ZERO, BigDecimal.ZERO, false, List.of()
                ),
                List.of(), List.of(positionSummary), false, List.of()
        );
        when(positionService.getUserPortfolio(any(), eq(portfolioId))).thenReturn(List.of(position));
        when(portfolioService.getPortfolioSummary(any(), eq(portfolioId))).thenReturn(summary);

        mockMvc.perform(get("/portfolios/{portfolioId}/positions", portfolioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].valuedThrough").value("2025-04-22"))
                .andExpect(jsonPath("$[0].pendingLatestObservation").value(true));

        mockMvc.perform(get("/portfolios/{portfolioId}/summary", portfolioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[0].valuedThrough").value("2025-04-22"))
                .andExpect(jsonPath("$.positions[0].pendingLatestObservation").value(true));
    }

    @Test
    void getPortfolioTargetsShouldReturnCategoryAndAssetTargets() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        UUID categoryTargetId = UUID.randomUUID();
        UUID assetTargetId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();

        when(smartRebalancingService.getTargets(any(), eq(portfolioId))).thenReturn(List.of(
                new PortfolioTargetResponseDTO(categoryTargetId, "FII", null, null, null, new BigDecimal("40.00")),
                new PortfolioTargetResponseDTO(assetTargetId, null, assetId, "WEGE3", "WEG S.A.", new BigDecimal("60.00"))
        ));

        mockMvc.perform(get("/portfolios/{portfolioId}/targets", portfolioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(categoryTargetId.toString()))
                .andExpect(jsonPath("$[0].assetCategory").value("FII"))
                .andExpect(jsonPath("$[0].assetId").doesNotExist())
                .andExpect(jsonPath("$[0].targetPercentage").value(40.0))
                .andExpect(jsonPath("$[1].id").value(assetTargetId.toString()))
                .andExpect(jsonPath("$[1].assetCategory").doesNotExist())
                .andExpect(jsonPath("$[1].assetId").value(assetId.toString()))
                .andExpect(jsonPath("$[1].ticker").value("WEGE3"))
                .andExpect(jsonPath("$[1].assetName").value("WEG S.A."))
                .andExpect(jsonPath("$[1].targetPercentage").value(60.0));
    }

    @Test
    void getPortfolioTargetsShouldReturnNotFoundWhenPortfolioDoesNotBelongToUser() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        when(smartRebalancingService.getTargets(any(), eq(portfolioId)))
                .thenThrow(new PortfolioNotFoundException("Carteira nao encontrada."));

        mockMvc.perform(get("/portfolios/{portfolioId}/targets", portfolioId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Carteira nao encontrada."));
    }

    @Test
    void savePortfolioTargetsShouldReturnNoContent() throws Exception {
        UUID portfolioId = UUID.randomUUID();

        mockMvc.perform(put("/portfolios/{portfolioId}/targets", portfolioId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"assetCategory": "ACOES", "targetPercentage": 50.00},
                                  {"assetCategory": "FII", "targetPercentage": 50.00}
                                ]
                                """))
                .andExpect(status().isNoContent());

        verify(smartRebalancingService).saveTargets(any(), eq(portfolioId), eq(List.of(
                SmartRebalancingService.PortfolioTargetInput.forCategory("ACOES", new BigDecimal("50.00")),
                SmartRebalancingService.PortfolioTargetInput.forCategory("FII", new BigDecimal("50.00"))
        )));
    }

    @Test
    void savePortfolioTargetsShouldReturnBadRequestWhenValidationFails() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        doThrow(new IllegalArgumentException("A soma das metas deve ser aproximadamente 100%. Atual: 80.00%"))
                .when(smartRebalancingService).saveTargets(any(), eq(portfolioId), any(List.class));

        mockMvc.perform(put("/portfolios/{portfolioId}/targets", portfolioId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"assetCategory": "ACOES", "targetPercentage": 80.00}
                                ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A soma das metas deve ser aproximadamente 100%. Atual: 80.00%"));
    }

    @Test
    void calculateRebalanceShouldReturnSuggestedOrders() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        SuggestedOrderDTO order = new SuggestedOrderDTO(
                "FII",
                null,
                null,
                new BigDecimal("3000.00"),
                new BigDecimal("5000.00"),
                new BigDecimal("2000.00"),
                new BigDecimal("1000.00"),
                BigDecimal.ZERO,
                null,
                null,
                null,
                true
        );
        when(smartRebalancingService.calculateSuggestedOrders(any(), eq(portfolioId), eq(new BigDecimal("1000.00"))))
                .thenReturn(List.of(order));

        mockMvc.perform(get("/portfolios/{portfolioId}/rebalance", portfolioId)
                        .param("contributionAmount", "1000.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("FII"))
                .andExpect(jsonPath("$[0].suggestedInvestment").value(1000.0));
    }

    @Test
    void calculateRebalanceShouldRejectNegativeContribution() throws Exception {
        UUID portfolioId = UUID.randomUUID();

        mockMvc.perform(get("/portfolios/{portfolioId}/rebalance", portfolioId)
                        .param("contributionAmount", "-10.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("O valor do aporte nao pode ser negativo."));
    }
}
