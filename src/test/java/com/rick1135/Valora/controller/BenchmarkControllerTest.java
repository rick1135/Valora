package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.BenchmarkStatusResponseDTO;
import com.rick1135.Valora.dto.response.BenchmarkSyncResponseDTO;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.service.BenchmarkSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BenchmarkControllerTest {
    private MockMvc mockMvc;

    @Mock
    private BenchmarkSyncService benchmarkSyncService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BenchmarkController(benchmarkSyncService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getStatusesReturnsBenchmarkCoverage() throws Exception {
        LocalDate latest = LocalDate.of(2026, 3, 20);
        when(benchmarkSyncService.getBenchmarkStatuses()).thenReturn(List.of(new BenchmarkStatusResponseDTO(
                "CDI", "CDI", "INTEREST_RATE", "BCB", latest.minusDays(2), latest, 2, 1, true)));

        mockMvc.perform(get("/benchmarks/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("CDI"))
                .andExpect(jsonPath("$[0].totalRecords").value(2))
                .andExpect(jsonPath("$[0].missingBusinessDaysLast30Days").value(1))
                .andExpect(jsonPath("$[0].upToDate").value(true));
    }

    @Test
    void postSyncBindsIsoDatesAndReturnsSummary() throws Exception {
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = LocalDate.of(2026, 3, 20);
        when(benchmarkSyncService.syncRange(start, end))
                .thenReturn(new BenchmarkSyncResponseDTO(start, end, 3, 10, List.of()));

        mockMvc.perform(post("/benchmarks/sync")
                        .param("startDate", start.toString())
                        .param("endDate", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.benchmarksProcessed").value(3))
                .andExpect(jsonPath("$.recordsInserted").value(10))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void postSyncWithoutDatesUsesDefaultRange() throws Exception {
        LocalDate end = LocalDate.of(2026, 3, 20);
        when(benchmarkSyncService.syncRange(null, null))
                .thenReturn(new BenchmarkSyncResponseDTO(end.minusYears(1), end, 1, 3, List.of()));

        mockMvc.perform(post("/benchmarks/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsInserted").value(3));
    }

    @Test
    void postSyncRejectsInvertedDates() throws Exception {
        mockMvc.perform(post("/benchmarks/sync")
                        .param("startDate", "2026-03-20")
                        .param("endDate", "2026-03-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("startDate deve ser anterior ou igual a endDate."));

        verifyNoInteractions(benchmarkSyncService);
    }
}
