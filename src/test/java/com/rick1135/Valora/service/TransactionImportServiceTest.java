package com.rick1135.Valora.service;

import com.rick1135.Valora.config.TestRedisConfig;
import com.rick1135.Valora.dto.request.TransactionDTO;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO.PreviewRow;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO.RowError;
import com.rick1135.Valora.dto.response.TransactionImportResultDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.AssetCategory;
import com.rick1135.Valora.entity.Portfolio;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.entity.UserRole;
import com.rick1135.Valora.exception.InsufficientPositionException;
import com.rick1135.Valora.exception.PortfolioNotFoundException;
import com.rick1135.Valora.repository.AssetRepository;
import com.rick1135.Valora.repository.PortfolioRepository;
import com.rick1135.Valora.repository.PositionRepository;
import com.rick1135.Valora.repository.TransactionRepository;
import com.rick1135.Valora.repository.UserRepository;
import com.rick1135.Valora.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private static final Instant NOW = Instant.parse("2025-01-20T15:00:00Z");
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
                        new BigDecimal("2.500"), new BigDecimal("35.50"), new BigDecimal("88.75"))),
                List.of(), new BigDecimal("88.75")));
        verifyNoInteractions(transactionService);
    }

    @Test
    void previewsB3WithFractionalTickerAndBrazilianNumbers() {
        stubPetr4();
        when(assetRepository.findVisibleByTickerIgnoreCase(user, "PETR4F")).thenReturn(List.of());
        String content = "Data do Negócio;Tipo de Movimentação;Mercado;Código de Negociação;Quantidade;Preço;Valor\r\n"
                + "10/01/2025;Venda;Fracionário;PETR4F;'1.234,56';\"R$ 35,50\";43826,88\r\n";

        TransactionImportPreviewDTO preview = service.preview(user, PORTFOLIO_ID, csv(content));

        assertThat(preview).isEqualTo(new TransactionImportPreviewDTO(1, 1, 0, List.of(),
                List.of(new PreviewRow(2, LocalDate.of(2025, 1, 10), "PETR4", TransactionType.SELL,
                        new BigDecimal("1234.56"), new BigDecimal("35.50"), new BigDecimal("43826.88"))),
                List.of(), new BigDecimal("43826.88")));
        verify(assetRepository).findVisibleByTickerIgnoreCase(user, "PETR4F");
        verify(assetRepository).findVisibleByTickerIgnoreCase(user, "PETR4");
    }

    @Test
    void previewsPortugueseHeadersAndCalculatesTotalValueIgnoringOptionalTotalColumn() {
        stubPetr4();
        String content = "Data da Compra;Compra/Venda;Código da Ação;Quantidade;Preço Unitário;Valor Total\n"
                + "10/01/2025;C;PETR4;10;13,47;\n"
                + "11/01/2025;V;PETR4;4;15,10;999,99\n";

        TransactionImportPreviewDTO preview = service.preview(user, PORTFOLIO_ID, csv(content));

        assertThat(preview.totalRows()).isEqualTo(2);
        assertThat(preview.validCount()).isEqualTo(2);
        assertThat(preview.errorCount()).isZero();
        assertThat(preview.totalVolume()).isEqualByComparingTo("195.10");
        assertThat(preview.validRows()).containsExactly(
                new PreviewRow(2, LocalDate.of(2025, 1, 10), "PETR4", TransactionType.BUY,
                        new BigDecimal("10"), new BigDecimal("13.47"), new BigDecimal("134.70")),
                new PreviewRow(3, LocalDate.of(2025, 1, 11), "PETR4", TransactionType.SELL,
                        new BigDecimal("4"), new BigDecimal("15.10"), new BigDecimal("60.40")));
        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Valor", "Valor Total"})
    void rejectsCsvWithoutExplicitUnitPriceBeforePreviewOrImport(String valueHeader) {
        String content = "Data do Negócio;Tipo de Movimentação;Código de Negociação;Quantidade;" + valueHeader + "\n"
                + "10/01/2025;Compra;PETR4;10;134,70\n";

        assertThatThrownBy(() -> service.preview(user, PORTFOLIO_ID, csv(content)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preco unitario");
        assertThatThrownBy(() -> service.importCsv(user, PORTFOLIO_ID, csv(content)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preco unitario");
        verifyNoInteractions(assetRepository, transactionService);
    }

    @Test
    void rejectsCsvWithoutDateColumnWithClearErrorMessage() {
        String content = "Compra/Venda;Código da Ação;Quantidade;Preço Unitário;Valor Total\n"
                + "Compra;PETR4;10;13,47;134,70\n";

        assertThatThrownBy(() -> service.preview(user, PORTFOLIO_ID, csv(content)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Colunas obrigatorias ausentes")
                .hasMessageContaining("data");
        verifyNoInteractions(assetRepository, transactionService);
    }

    @Test
    void prefersAnExactTickerWithFractionalSuffix() {
        stubMarketTime();
        Asset asset = new Asset();
        asset.setTicker("PETR4F");
        when(assetRepository.findVisibleByTickerIgnoreCase(user, "PETR4F")).thenReturn(List.of(asset));

        TransactionImportPreviewDTO preview = service.preview(user, PORTFOLIO_ID, csv(HEADER + BUY.replace("PETR4", "PETR4F")));

        assertThat(preview.validRows()).extracting(PreviewRow::ticker).containsExactly("PETR4F");
        verify(assetRepository).findVisibleByTickerIgnoreCase(user, "PETR4F");
        verifyNoMoreInteractions(assetRepository);
    }

    @Test
    void usesTheFirstVisibleAssetAndDoesNotFallBackToAnUnscopedLookup() {
        stubMarketTime();
        Asset privateAsset = new Asset();
        privateAsset.setId(UUID.randomUUID());
        privateAsset.setTicker("PETR4");
        privateAsset.setOwnerUser(user);
        Asset globalAsset = new Asset();
        globalAsset.setId(ASSET_ID);
        globalAsset.setTicker("PETR4");
        when(assetRepository.findVisibleByTickerIgnoreCase(user, "PRIVATE"))
                .thenReturn(List.of());
        when(assetRepository.findVisibleByTickerIgnoreCase(user, "PETR4"))
                .thenReturn(List.of(privateAsset, globalAsset));
        when(transactionService.processTransactionInCurrentTransaction(eq(user), any(), any()))
                .thenReturn(new TransactionService.TransactionResult(null, false));

        assertThat(service.preview(user, PORTFOLIO_ID, csv(HEADER + BUY.replace("PETR4", "PRIVATE"))).missingTickers())
                .containsExactly("PRIVATE");
        service.importCsv(user, PORTFOLIO_ID, csv(HEADER + BUY));

        ArgumentCaptor<TransactionDTO> transaction = ArgumentCaptor.forClass(TransactionDTO.class);
        verify(transactionService).processTransactionInCurrentTransaction(eq(user), transaction.capture(), any());
        assertThat(transaction.getValue().assetId()).isEqualTo(privateAsset.getId());
        verify(assetRepository).findVisibleByTickerIgnoreCase(user, "PRIVATE");
        verify(assetRepository).findVisibleByTickerIgnoreCase(user, "PETR4");
        verifyNoMoreInteractions(assetRepository);
    }

    @Test
    void keepsTickerLookupCompatibleWhenNoUserIsProvided() {
        stubMarketTime();
        Asset asset = new Asset();
        asset.setId(ASSET_ID);
        asset.setTicker("PETR4");
        when(assetRepository.findByTickerIgnoreCase("PETR4")).thenReturn(Optional.of(asset));

        assertThat(service.preview(null, PORTFOLIO_ID, csv(HEADER + BUY)).validCount()).isEqualTo(1);
        verifyNoInteractions(transactionService);
    }

    @Test
    void rejectsFutureInstantsInSaoPauloAndDecimalsOutsideStoragePrecision() {
        stubPetr4();
        String content = HEADER + "2025-01-20T12:00:01,PETR4,BUY,1,1\n"
                + "2025-01-20T15:00:01Z,PETR4,BUY,1,1\n"
                + "2025-01-20T12:00,PETR4,BUY,0.000000001,1\n"
                + "2025-01-20T12:00,PETR4,BUY,1,1.000000001\n"
                + "2025-01-20T12:00,PETR4,BUY,100000000000,1\n"
                + "2025-01-20T12:00,PETR4,BUY,0.000000010,99999999999.99999999\n";

        var preview = service.preview(user, PORTFOLIO_ID, csv(content));

        assertThat(preview.errors()).extracting(RowError::lineNumber).containsExactly(2, 3, 4, 5, 6);
        assertThat(preview.errors()).extracting(RowError::message).satisfiesExactly(
                message -> assertThat(message).contains("futura"),
                message -> assertThat(message).contains("futura"),
                message -> assertThat(message).contains("Quantidade", "0.00000001"),
                message -> assertThat(message).contains("Preco unitario", "8 casas decimais"),
                message -> assertThat(message).contains("Quantidade", "11 digitos inteiros"));
        assertThat(preview.validRows()).extracting(PreviewRow::lineNumber).containsExactly(7);
        assertThatThrownBy(() -> service.importCsv(user, PORTFOLIO_ID, csv(content)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactionService);
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
    void replaysReorderedRowsAndKeepsIdenticalPurchasesDistinct() {
        stubPetr4();
        Set<String> seen = new HashSet<>();
        when(transactionService.processTransactionInCurrentTransaction(eq(user), any(), any()))
                .thenAnswer(invocation -> new TransactionService.TransactionResult(
                        null, !seen.add(invocation.getArgument(2))));
        String sell = "2025-01-11T12:00:00Z,PETR4,SELL,1,40.00\n";
        String differentBuy = "2025-01-10T12:00,PETR4,BUY,3,34.00\n";
        String identicalBuy = "2025-01-10T15:00:00Z,PETR4,COMPRA,2.0,35.5000\n";

        var first = service.importCsv(user, PORTFOLIO_ID, csv(HEADER + sell + differentBuy + BUY + identicalBuy));
        var replay = service.importCsv(user, PORTFOLIO_ID, csv(HEADER + identicalBuy + BUY + sell + differentBuy));

        assertThat(first).isEqualTo(new TransactionImportResultDTO(4, 4, 0));
        assertThat(replay).isEqualTo(new TransactionImportResultDTO(4, 0, 4));
        ArgumentCaptor<TransactionDTO> transactions = ArgumentCaptor.forClass(TransactionDTO.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(transactionService, times(8))
                .processTransactionInCurrentTransaction(eq(user), transactions.capture(), keys.capture());

        Instant noonInSaoPaulo = Instant.parse("2025-01-10T15:00:00Z");
        assertThat(transactions.getAllValues().subList(0, 4)).containsExactly(
                new TransactionDTO(PORTFOLIO_ID, ASSET_ID, TransactionType.BUY, new BigDecimal("3"),
                        new BigDecimal("34.00"), noonInSaoPaulo),
                new TransactionDTO(PORTFOLIO_ID, ASSET_ID, TransactionType.BUY, new BigDecimal("2"),
                        new BigDecimal("35.50"), noonInSaoPaulo),
                new TransactionDTO(PORTFOLIO_ID, ASSET_ID, TransactionType.BUY, new BigDecimal("2.0"),
                        new BigDecimal("35.5000"), noonInSaoPaulo),
                new TransactionDTO(PORTFOLIO_ID, ASSET_ID, TransactionType.SELL, BigDecimal.ONE,
                        new BigDecimal("40.00"), Instant.parse("2025-01-11T12:00:00Z")));
        List<String> firstKeys = keys.getAllValues().subList(0, 4);
        assertThat(firstKeys).doesNotHaveDuplicates().allMatch(key -> key.matches("csv:[0-9a-f]{64}"));
        assertThat(keys.getAllValues().subList(4, 8)).containsExactlyInAnyOrderElementsOf(firstKeys);
    }

    @Test
    void preservesPhysicalRowOrderWhenSameDaySellAppearsBeforeBuy() {
        stubPetr4();
        List<TransactionType> processedTypes = new java.util.ArrayList<>();
        when(transactionService.processTransactionInCurrentTransaction(eq(user), any(), any()))
                .thenAnswer(invocation -> {
                    TransactionDTO dto = invocation.getArgument(1);
                    processedTypes.add(dto.type());
                    if (dto.type() == TransactionType.SELL) {
                        throw new InsufficientPositionException("Saldo insuficiente para venda.");
                    }
                    return new TransactionService.TransactionResult(null, false);
                });
        String sameDaySellFirst = HEADER
                + "2025-01-10,PETR4,SELL,1,36.00\n"
                + "2025-01-10,PETR4,BUY,2,35.50\n";

        assertThatThrownBy(() -> service.importCsv(user, PORTFOLIO_ID, csv(sameDaySellFirst)))
                .isInstanceOf(InsufficientPositionException.class);
        assertThat(processedTypes).containsExactly(TransactionType.SELL);
    }

    @Test
    void separatesIdenticalTradesImportedInDistinctBatches() {
        stubPetr4();
        Set<String> seen = new HashSet<>();
        when(transactionService.processTransactionInCurrentTransaction(eq(user), any(), any()))
                .thenAnswer(invocation -> new TransactionService.TransactionResult(
                        null, !seen.add(invocation.getArgument(2))));
        String otherDayBuy = "2025-01-11,PETR4,BUY,1,36.00\n";

        var batchA = service.importCsv(user, PORTFOLIO_ID, csv(HEADER + BUY));
        var batchB = service.importCsv(user, PORTFOLIO_ID, csv(HEADER + BUY + otherDayBuy));

        assertThat(batchA).isEqualTo(new TransactionImportResultDTO(1, 1, 0));
        assertThat(batchB).isEqualTo(new TransactionImportResultDTO(2, 2, 0));
    }

    private void stubPetr4() {
        stubMarketTime();
        Asset asset = new Asset();
        asset.setTicker("PETR4");
        asset.setId(ASSET_ID);
        when(assetRepository.findVisibleByTickerIgnoreCase(user, "PETR4")).thenReturn(List.of(asset));
    }

    private void stubMarketTime() {
        when(marketCalendar.today()).thenReturn(TODAY);
        when(marketCalendar.now()).thenReturn(NOW);
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "negociacoes.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}

@SpringBootTest
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class TransactionImportRollbackIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired
    private TransactionImportService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rollsBackTheFirstPurchaseWhenTheSecondRowOversells() {
        User user = new User();
        user.setEmail("csv-" + UUID.randomUUID() + "@valora.dev");
        user.setName("Investor");
        user.setPasswordHash("hash");
        user.setRole(UserRole.USER);
        userRepository.saveAndFlush(user);

        Portfolio portfolio = new Portfolio();
        portfolio.setName("CSV");
        portfolio.setUser(user);
        portfolioRepository.saveAndFlush(portfolio);

        Asset asset = new Asset();
        asset.setTicker(uniqueTicker("CSV"));
        asset.setName("Import rollback test");
        asset.setCategory(AssetCategory.ACOES);
        assetRepository.saveAndFlush(asset);

        try {
            String content = """
                    data,ticker,tipo,quantidade,preco_unitario
                    2025-01-10,%s,BUY,2,10
                    2025-01-11,%s,SELL,3,10
                    """.formatted(asset.getTicker(), asset.getTicker());
            var file = new MockMultipartFile("file", content.getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> service.importCsv(user, portfolio.getId(), file))
                    .isInstanceOf(InsufficientPositionException.class);
            assertThat(transactionRepository.findByPortfolioOrderByTransactionDateAscIdAsc(portfolio)).isEmpty();
            assertThat(positionRepository.findByPortfolioAndAsset(portfolio, asset)).isEmpty();
        } finally {
            jdbcTemplate.update("DELETE FROM transactions WHERE portfolio_id = ?", portfolio.getId());
            jdbcTemplate.update("DELETE FROM positions WHERE portfolio_id = ?", portfolio.getId());
            portfolioRepository.deleteById(portfolio.getId());
            assetRepository.deleteById(asset.getId());
            userRepository.deleteById(user.getId());
        }
    }
}
