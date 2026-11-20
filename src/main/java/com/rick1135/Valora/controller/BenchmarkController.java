package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.BenchmarkStatusResponseDTO;
import com.rick1135.Valora.dto.response.BenchmarkSyncResponseDTO;
import com.rick1135.Valora.service.BenchmarkSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/benchmarks")
@RequiredArgsConstructor
public class BenchmarkController {
    private final BenchmarkSyncService benchmarkSyncService;

    @GetMapping("/status")
    public ResponseEntity<List<BenchmarkStatusResponseDTO>> getStatuses() {
        return ResponseEntity.ok(benchmarkSyncService.getBenchmarkStatuses());
    }

    @PostMapping("/sync")
    public ResponseEntity<BenchmarkSyncResponseDTO> sync(
            @RequestParam(name = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate deve ser anterior ou igual a endDate.");
        }
        return ResponseEntity.ok(benchmarkSyncService.syncRange(startDate, endDate));
    }
}
