package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.PositionResponseDTO;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PortfolioControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PositionService positionService;

    @BeforeEach
    void setUp() {
        PortfolioController controller = new PortfolioController(positionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getPortfolioShouldReturnCurrentPositions() throws Exception {
        PositionResponseDTO position = new PositionResponseDTO(
                UUID.randomUUID(),
                "VALE3",
                "Vale",
                new BigDecimal("3.00000000"),
                new BigDecimal("60.00000000"),
                new BigDecimal("180.0000000000000000")
        );
        when(positionService.getUserPortfolio(any())).thenReturn(List.of(position));

        mockMvc.perform(get("/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("VALE3"))
                .andExpect(jsonPath("$[0].totalCost").value(180.0));
    }
}
