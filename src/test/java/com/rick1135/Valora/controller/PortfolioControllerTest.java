package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.AssetAllocationDTO;
import com.rick1135.Valora.dto.response.PortfolioSummaryDTO;
import com.rick1135.Valora.dto.response.PositionResponseDTO;
import com.rick1135.Valora.service.PortfolioService;
import com.rick1135.Valora.service.PositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PortfolioControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PositionService positionService;

    @Mock
    private PortfolioService portfolioService;

    @BeforeEach
    void setUp() {
        PortfolioController controller = new PortfolioController(positionService, portfolioService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
                new BigDecimal("100.00"),
                new BigDecimal("33.3333"),
                List.of(
                        new AssetAllocationDTO("ACOES", new BigDecimal("250.00"), new BigDecimal("71.4286")),
                        new AssetAllocationDTO("ETF", new BigDecimal("100.00"), new BigDecimal("28.5714"))
                )
        );
        when(portfolioService.getPortfolioSummary(any(), eq(portfolioId))).thenReturn(summary);

        mockMvc.perform(get("/portfolios/{portfolioId}/summary", portfolioId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPatrimony").value(350.0))
                .andExpect(jsonPath("$.allocations[0].category").value("ACOES"))
                .andExpect(jsonPath("$.allocations[0].percentage").value(71.4286));
    }
}
