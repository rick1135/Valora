package com.rick1135.Valora.service;

import com.rick1135.Valora.dto.request.TransactionDTO;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO.PreviewRow;
import com.rick1135.Valora.dto.response.TransactionImportPreviewDTO.RowError;
import com.rick1135.Valora.dto.response.TransactionImportResultDTO;
import com.rick1135.Valora.entity.Asset;
import com.rick1135.Valora.entity.TransactionType;
import com.rick1135.Valora.entity.User;
import com.rick1135.Valora.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TransactionImportService {
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ROWS = 2_000;
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZILIAN_DATE =
            DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern QUOTED_HEADER_FIELD = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern BRAZILIAN_DECIMAL = Pattern.compile("(?:[0-9]++|[0-9]{1,3}(?:\\.[0-9]{3})++),[0-9]+");
    private static final Pattern INTERNATIONAL_DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final BigDecimal MIN_DECIMAL = new BigDecimal("0.00000001");
    private static final BigDecimal DECIMAL_UPPER_BOUND = new BigDecimal("100000000000");
    private static final Comparator<ParsedRow> EXECUTION_ORDER = Comparator
            .comparing((ParsedRow row) -> row.transaction().transactionDate())
            .thenComparingInt(row -> row.preview().lineNumber());

    private final PortfolioService portfolioService;
    private final AssetRepository assetRepository;
    private final TransactionService transactionService;
    private final MarketCalendar marketCalendar;

    public TransactionImportPreviewDTO preview(User user, UUID portfolioId, MultipartFile file) {
        portfolioService.resolveOwnedPortfolio(user, portfolioId);
        ParsedCsv parsed = parse(user, portfolioId, file);
        return new TransactionImportPreviewDTO(parsed.totalRows(), parsed.rows().size(), parsed.errors().size(),
                parsed.missingTickers(), parsed.rows().stream().map(ParsedRow::preview).toList(), parsed.errors());
    }

    @Transactional
    public TransactionImportResultDTO importCsv(User user, UUID portfolioId, MultipartFile file) {
        portfolioService.resolveOwnedPortfolio(user, portfolioId);
        ParsedCsv parsed = parse(user, portfolioId, file);
        if (!parsed.errors().isEmpty() || !parsed.missingTickers().isEmpty()) {
            RowError first = parsed.errors().getFirst();
            String message = "CSV invalido. %d linha(s) com erro. Linha %d: %s"
                    .formatted(parsed.errors().size(), first.lineNumber(), first.message());
            if (!parsed.missingTickers().isEmpty()) {
                message += " Ativos nao cadastrados (%d): %s."
                        .formatted(parsed.missingTickers().size(),
                                String.join(", ", parsed.missingTickers().stream().limit(5).toList()));
            }
            throw new IllegalArgumentException(message);
        }

        // Preserva a ordem fisica original em caso de empate de data/hora (ex.: CSV somente com datas).
        List<ParsedRow> orderedRows = parsed.rows().stream()
                .sorted(EXECUTION_ORDER)
                .toList();
        String batchFingerprint = batchFingerprint(orderedRows);
        Map<String, Integer> occurrencesByTuple = new HashMap<>();
        int importedCount = 0;
        int skippedDuplicatesCount = 0;
        for (ParsedRow row : orderedRows) {
            TransactionDTO transaction = row.transaction();
            String tuple = canonicalTuple(transaction);
            int occurrence = occurrencesByTuple.merge(tuple, 1, Integer::sum);
            var result = transactionService.processTransactionInCurrentTransaction(
                    user, transaction, idempotencyKey(batchFingerprint, tuple, occurrence));
            if (result.replayed()) {
                skippedDuplicatesCount++;
            } else {
                importedCount++;
            }
        }
        return new TransactionImportResultDTO(parsed.totalRows(), importedCount, skippedDuplicatesCount);
    }

    private ParsedCsv parse(User user, UUID portfolioId, MultipartFile file) {
        String content = readCsv(file);
        var lines = content.lines().iterator();
        String header = lines.next();
        char delimiter = QUOTED_HEADER_FIELD.matcher(header).replaceAll("").contains(";") ? ';' : ',';
        List<String> headerFields = splitFields(header, delimiter).stream()
                .map(field -> Normalizer.normalize(field, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT))
                .toList();
        CsvColumns columns = CsvColumns.from(headerFields);
        LocalDate today = marketCalendar.today();
        List<ParsedRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        Set<String> missingTickers = new LinkedHashSet<>();
        Map<String, Optional<Asset>> assetsByTicker = new HashMap<>();
        int lineNumber = 1;
        int totalRows = 0;
        while (lines.hasNext()) {
            String line = lines.next();
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            if (++totalRows > MAX_ROWS) {
                throw new IllegalArgumentException("O CSV pode conter no maximo 2.000 linhas de dados.");
            }
            try {
                List<String> fields = splitFields(line, delimiter);
                if (fields.size() != headerFields.size()) {
                    throw new IllegalArgumentException("Quantidade de colunas diferente do cabecalho.");
                }
                Instant instant = parseDate(fields.get(columns.date()));
                LocalDate date = instant.atZone(MARKET_ZONE).toLocalDate();
                if (date.isAfter(today) || instant.isAfter(marketCalendar.now())) {
                    throw new IllegalArgumentException("Data da transacao nao pode ser futura.");
                }
                String ticker = fields.get(columns.ticker()).toUpperCase(Locale.ROOT);
                if (ticker.isEmpty()) {
                    throw new IllegalArgumentException("Ticker e obrigatorio.");
                }
                TransactionType type = parseType(fields.get(columns.type()));
                BigDecimal quantity = parsePositiveDecimal(fields.get(columns.quantity()), "Quantidade");
                BigDecimal price = parsePositiveDecimal(fields.get(columns.price()), "Preco unitario");
                Optional<Asset> asset = findAsset(user, ticker, assetsByTicker);
                if (asset.isEmpty()) {
                    missingTickers.add(ticker);
                    errors.add(new RowError(lineNumber, "Ativo '" + ticker + "' nao cadastrado."));
                    continue;
                }
                Asset resolved = asset.get();
                PreviewRow preview = new PreviewRow(lineNumber, date, resolved.getTicker(), type, quantity, price);
                TransactionDTO transaction = new TransactionDTO(portfolioId, resolved.getId(), type, quantity, price, instant);
                rows.add(new ParsedRow(preview, transaction));
            } catch (IllegalArgumentException exception) {
                errors.add(new RowError(lineNumber, exception.getMessage()));
            }
        }
        return new ParsedCsv(totalRows, rows, List.copyOf(errors), List.copyOf(missingTickers));
    }

    private String readCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("O arquivo CSV esta vazio.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("O arquivo CSV pode ter no maximo 2 MB.");
        }
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("O arquivo CSV pode ter no maximo 2 MB.");
            }
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (content.startsWith("\uFEFF")) {
                content = content.substring(1);
            }
            if (content.isBlank()) {
                throw new IllegalArgumentException("O arquivo CSV esta vazio.");
            }
            return content;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Nao foi possivel ler o arquivo CSV em UTF-8.", exception);
        }
    }

    private List<String> splitFields(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        char quote = 0;
        boolean closedQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (quote != 0) {
                if (current != quote) {
                    field.append(current);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == quote) {
                    field.append(current);
                    i++;
                } else {
                    quote = 0;
                    closedQuote = true;
                }
            } else if (current == delimiter) {
                fields.add(field.toString().strip());
                field.setLength(0);
                closedQuote = false;
            } else if (closedQuote) {
                if (!Character.isWhitespace(current)) {
                    throw new IllegalArgumentException("Campo CSV com aspas invalidas.");
                }
            } else if (field.isEmpty() && (current == '"' || current == '\'')) {
                quote = current;
            } else if (!field.isEmpty() || !Character.isWhitespace(current)) {
                field.append(current);
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Campo CSV com aspas nao fechadas.");
        }
        fields.add(field.toString().strip());
        return fields;
    }

    private Instant parseDate(String value) {
        try {
            if (value.endsWith("Z")) {
                return Instant.parse(value);
            }
            if (value.contains("T")) {
                return LocalDateTime.parse(value).atZone(MARKET_ZONE).toInstant();
            }
            LocalDate date = value.contains("/") ? LocalDate.parse(value, BRAZILIAN_DATE) : LocalDate.parse(value);
            return date.atTime(LocalTime.NOON).atZone(MARKET_ZONE).toInstant();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Data da transacao invalida.", exception);
        }
    }

    private TransactionType parseType(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "COMPRA", "BUY" -> TransactionType.BUY;
            case "VENDA", "SELL" -> TransactionType.SELL;
            default -> throw new IllegalArgumentException("Tipo de transacao invalido. Informe compra ou venda.");
        };
    }

    private BigDecimal parsePositiveDecimal(String value, String field) {
        if (value.startsWith("R$")) {
            value = value.substring(2).replace('\u00a0', ' ').strip();
        }
        if (BRAZILIAN_DECIMAL.matcher(value).matches()) {
            value = value.replace(".", "").replace(',', '.');
        } else if (!INTERNATIONAL_DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " deve ser um numero maior que zero.");
        }
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.compareTo(MIN_DECIMAL) < 0) {
            throw new IllegalArgumentException(field + " deve ser de pelo menos 0.00000001.");
        }
        if (decimal.compareTo(DECIMAL_UPPER_BOUND) >= 0 || decimal.stripTrailingZeros().scale() > 8) {
            throw new IllegalArgumentException(field + " deve ter ate 11 digitos inteiros e 8 casas decimais.");
        }
        return decimal;
    }

    private Optional<Asset> findAsset(User user, String ticker, Map<String, Optional<Asset>> assetsByTicker) {
        Optional<Asset> exact = assetsByTicker.computeIfAbsent(ticker, key -> findVisibleAsset(user, key));
        if (exact.isPresent() || ticker.length() == 1 || !ticker.endsWith("F")) {
            return exact;
        }
        String regularTicker = ticker.substring(0, ticker.length() - 1);
        return assetsByTicker.computeIfAbsent(regularTicker, key -> findVisibleAsset(user, key));
    }

    private Optional<Asset> findVisibleAsset(User user, String ticker) {
        if (user == null) {
            return assetRepository.findByTickerIgnoreCase(ticker);
        }
        return assetRepository.findVisibleByTickerIgnoreCase(user, ticker).stream().findFirst();
    }

    /**
     * Gera chave idempotente estavel para reenvios do mesmo lote (independentemente da ordem fisica das linhas),
     * preservando ocorrencias identicas dentro do lote e evitando colisao entre lotes distintos com conteudos diferentes.
     * Limitacao conhecida: dois arquivos distintos contendo exatamente o mesmo conjunto de linhas sem identificador
     * externo de negociacao nao podem ser distinguidos apenas pelo conteudo.
     */
    private String idempotencyKey(String batchFingerprint, String canonicalTuple, int occurrence) {
        return "csv:" + sha256Hex(batchFingerprint + "|" + canonicalTuple + "|" + occurrence);
    }

    private String batchFingerprint(List<ParsedRow> rows) {
        String canonicalBatch = rows.stream()
                .map(row -> canonicalTuple(row.transaction()))
                .sorted()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return sha256Hex(canonicalBatch);
    }

    private String canonicalTuple(TransactionDTO dto) {
        return dto.portfolioId() + "|" + dto.transactionDate()
                + "|" + dto.assetId() + "|" + dto.type() + "|" + dto.quantity().stripTrailingZeros()
                + "|" + dto.unitPrice().stripTrailingZeros();
    }

    private String sha256Hex(String payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel.", exception);
        }
    }

    private record ParsedRow(PreviewRow preview, TransactionDTO transaction) {
    }

    private record ParsedCsv(int totalRows, List<ParsedRow> rows, List<RowError> errors, List<String> missingTickers) {
    }

    private record CsvColumns(int date, int ticker, int type, int quantity, int price) {
        static CsvColumns from(List<String> header) {
            int quantity = find(header, "quantidade", "quantity");
            CsvColumns valora = new CsvColumns(find(header, "data", "date"), find(header, "ticker", "ativo"),
                    find(header, "tipo", "type"), quantity, find(header, "preco_unitario", "preco", "unit_price", "price"));
            if (valora.isComplete()) {
                return valora;
            }
            CsvColumns b3 = new CsvColumns(find(header, "data do negocio"), find(header, "codigo de negociacao"),
                    find(header, "tipo de movimentacao"), find(header, "quantidade"), find(header, "preco", "valor"));
            if (b3.isComplete()) {
                return b3;
            }
            throw new IllegalArgumentException("Cabecalho invalido. Use o modelo CSV Valora ou B3 Negociacoes.");
        }

        private boolean isComplete() {
            return date >= 0 && ticker >= 0 && type >= 0 && quantity >= 0 && price >= 0;
        }

        private static int find(List<String> header, String... aliases) {
            for (String alias : aliases) {
                int index = header.indexOf(alias);
                if (index >= 0) {
                    return index;
                }
            }
            return -1;
        }
    }
}
