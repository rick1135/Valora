package com.rick1135.Valora.controller;

import com.rick1135.Valora.dto.response.AssetResponseDTO;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.exception.AssetAlreadyExistsException;
import com.rick1135.Valora.exception.GlobalExceptionHandler;
import com.rick1135.Valora.service.AssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

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
class AssetControllerTest {
    private MockMvc mockMvc;

    @Mock
    private AssetService assetService;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        AssetController controller = new AssetController(assetService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createAssetReturnsCreatedWhenPayloadIsValid() throws Exception {
        UUID id = UUID.randomUUID();
        AssetResponseDTO response = new AssetResponseDTO(id, "PETR4", "Petrobras", AssetCategory.ACOES);
        when(assetService.createAsset(any())).thenReturn(response);

        mockMvc.perform(post("/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker": "petr4",
                                  "name": "Petrobras",
                                  "category": "ACOES"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("PETR4"))
                .andExpect(jsonPath("$.name").value("Petrobras"))
                .andExpect(jsonPath("$.category").value("ACOES"));
    }

    @Test
    void createAssetReturnsConflictWhenTickerAlreadyExists() throws Exception {
        when(assetService.createAsset(any())).thenThrow(new AssetAlreadyExistsException("Ativo com ticker 'PETR4' ja cadastrado."));

        mockMvc.perform(post("/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker": "PETR4",
                                  "name": "Petrobras",
                                  "category": "ACOES"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ativo com ticker 'PETR4' ja cadastrado."));
    }

    @Test
    void createAssetReturnsBadRequestWhenPayloadIsInvalid() throws Exception {
        mockMvc.perform(post("/assets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker": "***",
                                  "name": "",
                                  "category": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void searchAssetsReturnsMatchingAssets() throws Exception {
        AssetResponseDTO response = new AssetResponseDTO(UUID.randomUUID(), "PETR4", "Petrobras", AssetCategory.ACOES);
        when(assetService.searchAssets("petr")).thenReturn(List.of(response));

        mockMvc.perform(get("/assets/search")
                        .param("ticker", "petr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("PETR4"));
    }

    @Test
    void getAllAssetsReturnsPagedResult() throws Exception {
        AssetResponseDTO response = new AssetResponseDTO(UUID.randomUUID(), "ITSA4", "Itausa", AssetCategory.ACOES);
        when(assetService.getAllAssets(eq(0), eq(20), eq("ticker"), eq("asc")))
                .thenReturn(List.of(response));

        mockMvc.perform(get("/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("ITSA4"));
    }
}
