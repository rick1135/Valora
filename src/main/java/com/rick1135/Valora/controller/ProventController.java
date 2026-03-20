package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.request.ProventRequestDTO;
import com.rick1135.Valora.dto.response.ProventProvisionResponseDTO;
import com.rick1135.Valora.dto.response.ProventResponseDTO;
import com.rick1135.Valora.dto.response.ProventSyncResponseDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.service.ProventService;
import com.rick1135.Valora.service.ProventSyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/provents")
@RequiredArgsConstructor
public class ProventController {
    private final ProventService proventService;
    private final ProventSyncService proventSyncService;

    @PostMapping
    public ResponseEntity<ProventResponseDTO> createProvent(@Valid @RequestBody ProventRequestDTO dto) {
        ProventResponseDTO response = proventService.createProvent(dto);
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<Page<ProventProvisionResponseDTO>> getMyProvents(
            @AuthenticationPrincipal User user,
            @PageableDefault(sort = {"provent.paymentDate", "provent.comDate"}, direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(proventService.getMyProvents(user, pageable));
    }

    @PostMapping("/sync")
    public ResponseEntity<ProventSyncResponseDTO> syncProventsFromBrapi() {
        ProventSyncResponseDTO response = proventSyncService.syncFromCurrentPortfolioAssets();
        return ResponseEntity.ok(response);
    }
}
