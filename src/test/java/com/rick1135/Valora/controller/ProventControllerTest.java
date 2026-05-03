package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.ProventProvisionResponseDTO;
import com.rick1135.Valora.dto.response.ProventResponseDTO;
import com.rick1135.Valora.dto.response.ProventSyncResponseDTO;
import com.rick1135.Valora.entity.ProventStatus;
import com.rick1135.Valora.entity.ProventRateBasis;
import com.rick1135.Valora.entity.ProventSource;
import com.rick1135.Valora.entity.ProventType;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.service.ProventService;
import com.rick1135.Valora.service.ProventSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProventControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ProventService proventService;
    @Mock
    private ProventSyncService proventSyncService;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        ProventController controller = new ProventController(proventService, proventSyncService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void createProventShouldReturnCreated() throws Exception {
        UUID proventId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        when(proventService.createProvent(any())).thenReturn(new ProventResponseDTO(
                proventId,
                assetId,
                "ITSA4",
                ProventType.DIVIDEND,
                new BigDecimal("1.23000000"),
                LocalDate.of(2026, 3, 20),
                LocalDate.of(2026, 3, 30),
                2,
                ProventSource.MANUAL,
                "origin-key",
                ProventRateBasis.NET
        ));

        mockMvc.perform(post("/provents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "assetId": "%s",
                                  "type": "DIVIDEND",
                                  "amountPerShare": 1.23,
                                  "comDate": "2026-03-20",
                                  "paymentDate": "2026-03-30"
                                }
                                """.formatted(assetId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("ITSA4"))
                .andExpect(jsonPath("$.provisionedUsers").value(2));
    }

    @Test
    void createProventShouldReturnBadRequestWhenPayloadIsInvalid() throws Exception {
        mockMvc.perform(post("/provents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "DIVIDEND",
                                  "amountPerShare": 0,
                                  "comDate": "2026-03-20",
                                  "paymentDate": "2026-03-30"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void getMyProventsShouldReturnPagedContent() throws Exception {
        UUID portfolioId = UUID.randomUUID();
        when(proventService.getMyProvents(nullable(com.rick1135.Valora.entity.User.class), eq(portfolioId), any())).thenReturn(new PageImpl<ProventProvisionResponseDTO>(List.of(
                new ProventProvisionResponseDTO(
                        UUID.randomUUID(),
                        portfolioId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "TAEE11",
                        ProventType.JCP,
                        new BigDecimal("1.00000000"),
                        LocalDate.of(2026, 3, 20),
                        LocalDate.of(2026, 3, 30),
                        new BigDecimal("10.00000000"),
                        new BigDecimal("10.00000000"),
                        new BigDecimal("1.50000000"),
                        new BigDecimal("8.50000000"),
                        ProventStatus.PENDING,
                        ProventSource.BRAPI,
                        "brapi-key",
                        ProventRateBasis.GROSS
                )
        ), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/provents/me").param("portfolioId", portfolioId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticker").value("TAEE11"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    void getMyProventsShouldReturnBadRequestWhenPortfolioIdIsMissing() throws Exception {
        mockMvc.perform(get("/provents/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("portfolioId: parametro obrigatorio."));
    }

    @Test
    void getMyProventsShouldReturnBadRequestWhenPortfolioIdIsInvalid() throws Exception {
        mockMvc.perform(get("/provents/me").param("portfolioId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("portfolioId: valor invalido."));
    }

    @Test
    void syncProventsShouldReturnSummary() throws Exception {
        when(proventSyncService.syncFromCurrentPortfolioAssets())
                .thenReturn(new ProventSyncResponseDTO(5, 20, 4, 12, 4, 1));

        mockMvc.perform(post("/provents/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetsScanned").value(5))
                .andExpect(jsonPath("$.proventsCreated").value(4))
                .andExpect(jsonPath("$.integrationErrors").value(1));
    }
}
