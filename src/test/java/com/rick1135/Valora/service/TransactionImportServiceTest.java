package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.TransactionDTO;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO.PreviewRow;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO.RowError;
import com.rick1135.Valora.dto.response.TransactionImportResultDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.repository.AssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionImportServiceTest {
    private static final String HEADER = "data,ticker,tipo,quantidade,preco_unitario\n";
    private static final String BUY = "2025-01-10,PETR4,BUY,2,35.50\n";
    private static final LocalDate TODAY = LocalDate.of(2025, 1, 20);
    private static final UUID PORTFOLIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private PortfolioService portfolioService;

    @Mock
    private AssetRepository assetRepository;

    @Mock
    private TransactionService transactionService;

    @Mock
    private MarketCalendar marketCalendar;

    @InjectMocks
    private TransactionImportService service;

    private final User user = new User();

    @Test
    void previewsValoraWithBomAliasesAndQuotedFields() {
        stubPetr4();
        String content = "\uFEFF\"DATE\",Ativo,Type,Quantity,Preço\n"
                + "2025-01-10,' petr4 ',buy,2.500,\"35,50\"\n";

        TransactionImportPreviewDTO preview = service.preview(user, PORTFOLIO_ID, csv(content));

        verify(portfolioService).resolveOwnedPortfolio(user, PORTFOLIO_ID);
        assertThat(preview).isEqualTo(new TransactionImportPreviewDTO(1, 1, 0, List.of(),
                List.of(new PreviewRow(2, LocalDate.of(2025, 1, 10), "PETR4", TransactionType.BUY,
                        new BigDecimal("2.500"), new BigDecimal("35.50"))), List.of()));
        verifyNoInteractions(transactionService);
    }

    @Test
    void previewsB3WithFractionalTickerAndBrazilianNumbers() {
        stubPetr4();
        when(assetRepository.findByTickerIgnoreCase("PETR4F")).thenReturn(Optional.empty());
        String content = "Data do Negócio;Tipo de Movimentação;Mercado;Código de Negociação;Quantidade;Valor\r\n"
                + "10/01/2025;Venda;Fracionário;PETR4F;'1.234,56';\"R$ 35,50\"\r\n";

        TransactionImportPreviewDTO preview = service.preview(user, PORTFOLIO_ID, csv(content));

        assertThat(preview).isEqualTo(new TransactionImportPreviewDTO(1, 1, 0, List.of(),
                List.of(new PreviewRow(2, LocalDate.of(2025, 1, 10), "PETR4", TransactionType.SELL,
                        new BigDecimal("1234.56"), new BigDecimal("35.50"))), List.of()));
        verify(assetRepository).findByTickerIgnoreCase("PETR4F");
        verify(assetRepository).findByTickerIgnoreCase("PETR4");
    }

    @Test
    void prefersAnExactTickerWithFractionalSuffix() {
        when(marketCalendar.today()).thenReturn(TODAY);
        Asset asset = new Asset();
        asset.setTicker("PETR4F");
        when(assetRepository.findByTickerIgnoreCase("PETR4F")).thenReturn(Optional.of(asset));

        TransactionImportPreviewDTO preview = service.preview(user, PORTFOLIO_ID, csv(HEADER + BUY.replace("PETR4", "PETR4F")));

        assertThat(preview.validRows()).extracting(PreviewRow::ticker).containsExactly("PETR4F");
        verify(assetRepository).findByTickerIgnoreCase("PETR4F");
        verifyNoMoreInteractions(assetRepository);
    }

    @Test
    void reportsInvalidLinesAndUniqueMissingTickersBeforeImportingAnything() {
        stubPetr4();
        String content = HEADER + BUY + "\n"
                + "31/02/2025,PETR4,BUY,2,35.50\n"
                + "2025-01-21,PETR4,BUY,2,35.50\n"
                + "2025-01-10,PETR4,TRANSFER,2,35.50\n"
                + "2025-01-10,PETR4,BUY,0,35.50\n"
                + "2025-01-10,PETR4,BUY,2,-1\n"
                + "2025-01-10,unknown,BUY,2,35.50\n"
                + "2025-01-10,UNKNOWN,SELL,2,35.50\n";

        TransactionImportPreviewDTO preview = service.preview(user, PORTFOLIO_ID, csv(content));

        assertThat(preview.totalRows()).isEqualTo(8);
        assertThat(preview.validCount()).isEqualTo(1);
        assertThat(preview.errorCount()).isEqualTo(7);
        assertThat(preview.missingTickers()).containsExactly("UNKNOWN");
        assertThat(preview.errors()).extracting(RowError::lineNumber).containsExactly(4, 5, 6, 7, 8, 9, 10);
        assertThat(preview.errors().get(1).message()).contains("futura");
        assertThatThrownBy(() -> service.importCsv(user, PORTFOLIO_ID, csv(content)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7 linha(s) com erro").hasMessageContaining("UNKNOWN");
        verifyNoInteractions(transactionService);
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> service.preview(user, PORTFOLIO_ID, csv("")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("vazio");
        assertThatThrownBy(() -> service.preview(user, PORTFOLIO_ID, csv("\uFEFF \r\n")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("vazio");
        MockMultipartFile oversized = new MockMultipartFile("file", new byte[2 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> service.preview(user, PORTFOLIO_ID, oversized))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 MB");
        verifyNoInteractions(assetRepository, transactionService);
    }

    @Test
    void acceptsTheSizeAndRowLimitsAndRejectsAnExtraDataRow() {
        stubPetr4();
        String content = HEADER + BUY.repeat(2_000);
        String atSizeLimit = content + " ".repeat(2 * 1024 * 1024 - content.length());

        assertThat(service.preview(user, PORTFOLIO_ID, csv(atSizeLimit)).validCount()).isEqualTo(2_000);
        assertThatThrownBy(() -> service.importCsv(user, PORTFOLIO_ID, csv(content + BUY)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2.000 linhas de dados");
        verifyNoInteractions(transactionService);
    }

    @Test
    void validatesOwnershipBeforeParsing() {
        when(portfolioService.resolveOwnedPortfolio(user, PORTFOLIO_ID))
                .thenThrow(new PortfolioNotFoundException("Carteira nao encontrada."));

        assertThatThrownBy(() -> service.preview(user, PORTFOLIO_ID, csv("")))
                .isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.importCsv(user, PORTFOLIO_ID, csv("")))
                .isInstanceOf(PortfolioNotFoundException.class);
        verifyNoInteractions(assetRepository, transactionService);
    }

    @Test
    void importsChronologicallyAndCountsReplaysWithTheSameNormalizedKeys() {
        stubPetr4();
        when(transactionService.processTransaction(eq(user), any(TransactionDTO.class), any(String.class)))
                .thenReturn(new TransactionService.TransactionResult(null, false),
                        new TransactionService.TransactionResult(null, false),
                        new TransactionService.TransactionResult(null, false),
                        new TransactionService.TransactionResult(null, true));
        String content = HEADER + "2025-01-11T12:00:00Z,PETR4,SELL,1,40.00\n"
                + BUY + "2025-01-10T12:00,PETR4,BUY,3,34.00\n";

        TransactionImportResultDTO first = service.importCsv(user, PORTFOLIO_ID, csv(content));
        TransactionImportResultDTO replay = service.importCsv(user, PORTFOLIO_ID, csv(content.replace("35.50", "35.5000")));

        assertThat(first).isEqualTo(new TransactionImportResultDTO(3, 3, 0));
        assertThat(replay).isEqualTo(new TransactionImportResultDTO(3, 0, 3));
        ArgumentCaptor<TransactionDTO> transactions = ArgumentCaptor.forClass(TransactionDTO.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(transactionService, times(6)).processTransaction(eq(user), transactions.capture(), keys.capture());

        Instant noonInSaoPaulo = Instant.parse("2025-01-10T15:00:00Z");
        assertThat(transactions.getAllValues().subList(0, 3)).containsExactly(
                new TransactionDTO(PORTFOLIO_ID, ASSET_ID, TransactionType.BUY, new BigDecimal("2"),
                        new BigDecimal("35.50"), noonInSaoPaulo),
                new TransactionDTO(PORTFOLIO_ID, ASSET_ID, TransactionType.BUY, new BigDecimal("3"),
                        new BigDecimal("34.00"), noonInSaoPaulo),
                new TransactionDTO(PORTFOLIO_ID, ASSET_ID, TransactionType.SELL, BigDecimal.ONE,
                        new BigDecimal("40.00"), Instant.parse("2025-01-11T12:00:00Z")));
        List<String> firstKeys = keys.getAllValues().subList(0, 3);
        assertThat(firstKeys).doesNotHaveDuplicates().allMatch(key -> key.matches("csv:[0-9a-f]{64}"));
        assertThat(keys.getAllValues().subList(3, 6)).containsExactlyElementsOf(firstKeys);
    }

    private void stubPetr4() {
        when(marketCalendar.today()).thenReturn(TODAY);
        Asset asset = new Asset();
        asset.setTicker("PETR4");
        asset.setId(ASSET_ID);
        when(assetRepository.findByTickerIgnoreCase("PETR4")).thenReturn(Optional.of(asset));
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "negociacoes.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
