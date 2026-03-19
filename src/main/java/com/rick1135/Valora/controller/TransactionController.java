package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.request.TransactionDTO;
import com.rick1135.Valora.dto.response.TransactionResponseDTO;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {
    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<TransactionResponseDTO> registerTransaction(@AuthenticationPrincipal User user, @Valid @RequestBody TransactionDTO dto) {
        TransactionResponseDTO response = transactionService.processTransaction(user, dto);
        return ResponseEntity.status(201).body(response);
    }
}
