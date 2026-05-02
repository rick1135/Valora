package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.TransactionResponseDTO;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.exception.AssetNotFoundException;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.service.TransactionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TransactionControllerTest {

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
        TransactionResponseDTO response = new TransactionResponseDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PETR4",
                TransactionType.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10.00000000"),
                Instant.parse("2026-03-19T18:00:00Z"),
                new BigDecimal("1.00000000"),
                new BigDecimal("10.00000000")
        );
        when(transactionService.processTransaction(any(), any())).thenReturn(response);

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assetId": "11111111-1111-1111-1111-111111111111",
                                  "type": "BUY",
                                  "quantity": 1.00000000,
                                  "unitPrice": 10.00000000,
                                  "transactionDate": "2026-03-19T18:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("PETR4"))
                .andExpect(jsonPath("$.positionQuantity").value(1.0));
    }

    @Test
    void registerTransactionShouldReturnNotFoundWhenAssetDoesNotExist() throws Exception {
        when(transactionService.processTransaction(any(), any()))
                .thenThrow(new AssetNotFoundException("Ativo nao encontrado"));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
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
    void getTransactionHistoryShouldReturnPagedFilteredResult() throws Exception {
        TransactionResponseDTO response = new TransactionResponseDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PETR4",
                TransactionType.BUY,
                new BigDecimal("1.00000000"),
                new BigDecimal("10.00000000"),
                Instant.parse("2026-03-19T18:00:00Z"),
                null,
                null
        );

        when(transactionService.getTransactionHistory(any(), eq("PETR4"), eq(TransactionType.BUY), any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/transactions")
                        .param("ticker", "PETR4")
                        .param("type", "BUY")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticker").value("PETR4"))
                .andExpect(jsonPath("$.content[0].type").value("BUY"))
                .andExpect(jsonPath("$.size").value(20));
    }
}
