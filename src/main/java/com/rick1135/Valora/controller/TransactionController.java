package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.request.TransactionDTO;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO;
import com.rick1135.Valora.dto.response.TransactionImportResultDTO;
import com.rick1135.Valora.dto.response.TransactionResponseDTO;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.service.TransactionImportService;
import com.rick1135.Valora.service.TransactionService;
import jakarta.servlet.MultipartConfigElement;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/transactions")
public class TransactionController {
    private final TransactionService transactionService;
    private final TransactionImportService transactionImportService;

    @Autowired
    public TransactionController(TransactionService transactionService, TransactionImportService transactionImportService) {
        this.transactionService = transactionService;
        this.transactionImportService = transactionImportService;
    }

    public TransactionController(TransactionService transactionService) {
        this(transactionService, null);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "spring.servlet.multipart", name = "enabled", matchIfMissing = true)
    static MultipartConfigElement transactionMultipartConfig(MultipartProperties properties, Environment environment) {
        MultipartConfigElement config = properties.createMultipartConfig();
        // Spring Boot defaults to 1 MB per file. Keep any explicit deployment override.
        long maxFileSize = Binder.get(environment).bind("spring.servlet.multipart.max-file-size", DataSize.class)
                .orElse(DataSize.ofBytes(TransactionImportService.MAX_FILE_BYTES)).toBytes();
        return new MultipartConfigElement(config.getLocation(), maxFileSize, config.getMaxRequestSize(),
                config.getFileSizeThreshold());
    }

    @PostMapping
    public ResponseEntity<TransactionResponseDTO> registerTransaction(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody TransactionDTO dto,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        TransactionService.TransactionResult result = transactionService.processTransaction(user, dto, idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.response());
    }

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TransactionImportPreviewDTO> previewImport(
            @AuthenticationPrincipal User user,
            @RequestParam UUID portfolioId,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(transactionImportService.preview(user, portfolioId, file));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TransactionImportResultDTO> importTransactions(
            @AuthenticationPrincipal User user,
            @RequestParam UUID portfolioId,
            @RequestPart("file") MultipartFile file
    ) {
        return ResponseEntity.ok(transactionImportService.importCsv(user, portfolioId, file));
    }

    @PutMapping("/{transactionId}")
    public ResponseEntity<TransactionResponseDTO> updateTransaction(
            @AuthenticationPrincipal User user,
            @PathVariable UUID transactionId,
            @Valid @RequestBody TransactionDTO dto
    ) {
        return ResponseEntity.ok(transactionService.updateTransaction(user, transactionId, dto));
    }

    @DeleteMapping("/{transactionId}")
    public ResponseEntity<Void> deleteTransaction(
            @AuthenticationPrincipal User user,
            @PathVariable UUID transactionId
    ) {
        transactionService.deleteTransaction(user, transactionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<Page<TransactionResponseDTO>> getTransactionHistory(
            @AuthenticationPrincipal User user,
            @RequestParam UUID portfolioId,
            @RequestParam(required = false) String ticker,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @PageableDefault(size = 20, sort = "transactionDate", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(transactionService.getTransactionHistory(
                user,
                portfolioId,
                ticker,
                type,
                startDate,
                endDate,
                pageable
        ));
    }
}
