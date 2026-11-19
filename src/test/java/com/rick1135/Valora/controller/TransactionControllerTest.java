package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.TransactionResponseDTO;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.exception.InsufficientPositionException;
import com.rick1135.Valora.exception.TransactionNotFoundException;
import com.rick1135.Valora.service.TransactionService;
import com.rick1135.Valora.service.TransactionService.TransactionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TransactionControllerTest {

    private static final String VALID_PAYLOAD = """
            {
              "portfolioId": "22222222-2222-2222-2222-222222222222",
              "assetId": "11111111-1111-1111-1111-111111111111",
              "type": "BUY",
              "quantity": 1,
              "unitPrice": 10,
              "transactionDate": "2026-03-19T18:00:00Z"
            }
            """;

    private MockMvc mockMvc;

    @Mock
    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        TransactionController controller = new TransactionController(transactionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setValidator(validator)
                .build();
    }

    @Test
    void registerTransactionShouldReturnCreated() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        TransactionResponseDTO response = new TransactionResponseDTO(
                UUID.randomUUID(),
                portfolioId,
                UUID.randomUUID(),
                "PETR4",
                TransactionType.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10.00000000"),
                Instant.parse("2026-03-19T18:00:00Z"),
                new BigDecimal("1.00000000"),
                new BigDecimal("10.00000000")
        );
        when(transactionService.processTransaction(any(), any(), eq(null))).thenReturn(new TransactionResult(response, false));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "portfolioId": "%s",
                                  "assetId": "11111111-1111-1111-1111-111111111111",
                                  "type": "BUY",
                                  "quantity": 1.00000000,
                                  "unitPrice": 10.00000000,
                                  "transactionDate": "2026-03-19T18:00:00Z"
                                }
                                """.formatted(portfolioId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("PETR4"))
                .andExpect(jsonPath("$.positionQuantity").value(1.0));
    }

    @Test
    void registerTransactionShouldReturnNotFoundWhenAssetDoesNotExist() throws Exception {
        when(transactionService.processTransaction(any(), any(), eq(null)))
                .thenThrow(new AssetNotFoundException("Ativo nao encontrado"));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "portfolioId": "22222222-2222-2222-2222-222222222222",
                                  "assetId": "11111111-1111-1111-1111-111111111111",
                                  "type": "BUY",
                                  "quantity": 1.00000000,
                                  "unitPrice": 10.00000000,
                                  "transactionDate": "2026-03-19T18:00:00Z"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Ativo nao encontrado"));
    }

    @Test
    void registerTransactionShouldReturnBadRequestWhenPayloadIsInvalid() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "portfolioId": "22222222-2222-2222-2222-222222222222",
                                  "assetId": null,
                                  "type": "BUY",
                                  "quantity": 0,
                                  "unitPrice": 10.00000000,
                                  "transactionDate": "2099-03-19T18:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void registerTransactionShouldReturnOkForReplayAndBadRequestForKeyConflict() throws Exception {
        UUID transactionId = UUID.randomUUID();
        when(transactionService.processTransaction(any(), any(), eq("request-1")))
                .thenReturn(new TransactionResult(responseFor(transactionId), true))
                .thenThrow(new IllegalArgumentException("Idempotency-Key ja utilizada com dados diferentes."));

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transactionId.toString()));

        mockMvc.perform(post("/transactions")
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD.replace("\"quantity\": 1", "\"quantity\": 2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key ja utilizada com dados diferentes."));
    }

    @Test
    void updateTransactionShouldReturnUpdatedTransactionAndValidatePayload() throws Exception {
        UUID transactionId = UUID.randomUUID();
        when(transactionService.updateTransaction(any(), eq(transactionId), any()))
                .thenReturn(responseFor(transactionId));

        mockMvc.perform(put("/transactions/{transactionId}", transactionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transactionId.toString()));

        mockMvc.perform(put("/transactions/{transactionId}", transactionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteTransactionShouldReturnNoContentOrBadRequestWhenSaleWouldBecomeInvalid() throws Exception {
        UUID transactionId = UUID.randomUUID();
        UUID invalidDeletion = UUID.randomUUID();

        mockMvc.perform(delete("/transactions/{transactionId}", transactionId))
                .andExpect(status().isNoContent());
        org.mockito.Mockito.verify(transactionService).deleteTransaction(any(), eq(transactionId));

        org.mockito.Mockito.doThrow(new InsufficientPositionException("Quantidade insuficiente para venda"))
                .when(transactionService).deleteTransaction(any(), eq(invalidDeletion));

        mockMvc.perform(delete("/transactions/{transactionId}", invalidDeletion))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateAndDeleteShouldReturnNotFoundForUnavailableTransaction() throws Exception {
        UUID transactionId = UUID.randomUUID();
        TransactionNotFoundException notFound = new TransactionNotFoundException("Transacao nao encontrada.");
        when(transactionService.updateTransaction(any(), eq(transactionId), any())).thenThrow(notFound);
        org.mockito.Mockito.doThrow(notFound).when(transactionService).deleteTransaction(any(), eq(transactionId));

        mockMvc.perform(put("/transactions/{transactionId}", transactionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/transactions/{transactionId}", transactionId))
                .andExpect(status().isNotFound());
    }

    private TransactionResponseDTO responseFor(UUID transactionId) {
        return new TransactionResponseDTO(transactionId, UUID.randomUUID(), UUID.randomUUID(), "PETR4",
                TransactionType.BUY, BigDecimal.ONE, BigDecimal.TEN, Instant.parse("2026-03-19T18:00:00Z"),
                BigDecimal.ONE, BigDecimal.TEN);
    }

    @Test
    void getTransactionHistoryShouldReturnPagedFilteredResult() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        TransactionResponseDTO response = new TransactionResponseDTO(
                UUID.randomUUID(),
                portfolioId,
                UUID.randomUUID(),
                "PETR4",
                TransactionType.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10.00000000"),
                Instant.parse("2026-03-19T18:00:00Z"),
                null,
                null
        );

        Instant startDate = Instant.parse("2026-03-01T00:00:00Z");
        Instant endDate = Instant.parse("2026-03-31T23:59:59Z");

        when(transactionService.getTransactionHistory(
                any(),
                eq(portfolioId),
                eq("PETR4"),
                eq(TransactionType.BUY),
                eq(startDate),
                eq(endDate),
                any()
        ))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/transactions")
                        .param("portfolioId", portfolioId.toString())
                        .param("ticker", "PETR4")
                        .param("type", "BUY")
                        .param("startDate", "2026-03-01T00:00:00Z")
                        .param("endDate", "2026-03-31T23:59:59Z")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticker").value("PETR4"))
                .andExpect(jsonPath("$.content[0].type").value("BUY"))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void getTransactionHistoryShouldReturnBadRequestWhenPortfolioIdIsMissing() throws Exception {
        mockMvc.perform(get("/transactions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("portfolioId: parametro obrigatorio."));
    }

    @Test
    void getTransactionHistoryShouldReturnBadRequestWhenPortfolioIdIsInvalid() throws Exception {
        mockMvc.perform(get("/transactions").param("portfolioId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("portfolioId: valor invalido."));
    }
}
