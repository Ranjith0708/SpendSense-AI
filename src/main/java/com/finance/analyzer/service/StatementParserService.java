package com.finance.analyzer.service;

import com.finance.analyzer.config.AnalyzerProperties;
import com.finance.analyzer.dto.StatementChunk;
import com.finance.analyzer.exception.StatementParseException;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class StatementParserService {

    /** A line that starts with a date (or "Date=" for CSV rows) begins a new transaction record. */
    private static final Pattern RECORD_START = Pattern.compile(
            "^(?:[A-Za-z ]{1,20}=)?\\s*(?:\\d{1,2}[-/. ](?:\\d{1,2}|[A-Za-z]{3,9})[-/., ]+\\d{2,4}"
          + "|\\d{4}-\\d{2}-\\d{2}|[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})");
    private static final Pattern NOISE = Pattern.compile(
            "(?i)^(page\\s*\\d+(\\s*(of|/)\\s*\\d+)?|\\d+\\s*/\\s*\\d+|.*system[- ]generated.*|.*computer[- ]generated.*"
          + "|.*customer care.*|.*toll[- ]free.*|.*registered office.*|.*www\\..*|.*terms (and|&) conditions.*"
          + "|.*disclaimer.*|.*does not require.*signature.*"
          + "|date transaction details type amount|transaction statement for .*"
          + "|[A-Za-z]{3} \\d{1,2}, \\d{4} - [A-Za-z]{3} \\d{1,2}, \\d{4}"
          + "|.*immediately notify.*|.*fictitious offers.*|.*confidential and intended.*|.*support\\.phonepe\\.com.*"
          + "|.*privacy[- ]policy.*|.*received this message by mistake.*|.*recipient's details.*|emails and calls\\.)$");

    private final AnalyzerProperties.Chunking chunking;

    public StatementParserService(AnalyzerProperties props) {
        this.chunking = props.chunking();
    }

    /** Extracts the statement text and splits it into sequentially numbered chunks of whole transaction records. */
    public List<StatementChunk> parse(MultipartFile file, String password) {
        if (file == null || file.isEmpty()) throw new StatementParseException("Please upload a non-empty file.");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        log.info("parse_start file={} sizeBytes={}", name, file.getSize());
        try {
            byte[] bytes = file.getBytes();
            String raw;
            if (name.endsWith(".pdf")) raw = readPdf(bytes, password);
            else if (name.endsWith(".csv")) raw = readCsv(bytes);
            else if (name.endsWith(".xlsx") || name.endsWith(".xls")) raw = readExcel(bytes);
            else throw new StatementParseException("Unsupported file type. Upload a PDF, CSV, XLSX or XLS statement.");
            List<String> lines = clean(raw);
            log.debug("parse_extracted file={} chars={} cleanLines={}", name, raw.length(), lines.size());
            List<StatementChunk> chunks = chunk(lines);
            if (chunks.isEmpty()) {
                throw new StatementParseException(
                        "No readable text found. Scanned or image-only statements are not supported; download the digital statement instead.");
            }
            log.info("parse_complete file={} totalChunks={} transactionsPerChunk={}", name, chunks.size(), chunking.transactionsPerChunk());
            return chunks;
        } catch (StatementParseException e) {
            throw e;
        } catch (Exception e) {
            throw new StatementParseException("Could not read the file: " + e.getMessage(), e);
        }
    }

    private String readPdf(byte[] bytes, String password) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes, password == null ? "" : password)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (InvalidPasswordException e) {
            throw new StatementParseException("This PDF is password protected. Enter the correct password and retry.");
        }
    }

    private String readCsv(byte[] bytes) throws Exception {
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).build();
        StringBuilder sb = new StringBuilder();
        try (CSVParser p = fmt.parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            for (CSVRecord r : p) {
                sb.append(r.toMap().entrySet().stream()
                        .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(" | "))).append('\n');
            }
        }
        return sb.toString();
    }

    private String readExcel(byte[] bytes) throws Exception {
        DataFormatter fmt = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : wb) {
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell c : row) {
                        String v = fmt.formatCellValue(c).trim();
                        if (!v.isEmpty()) cells.add(v);
                    }
                    if (!cells.isEmpty()) sb.append(String.join(" | ", cells)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private List<String> clean(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String l = line.replaceAll("\\s+", " ").trim();
            if (l.length() < 2 || NOISE.matcher(l).matches()) continue;
            out.add(l);
        }
        return out;
    }

    /** Groups lines into transaction records, then packs N records per chunk and numbers the chunks. */
    List<StatementChunk> chunk(List<String> lines) {
        List<String> records = toRecords(lines);
        int perChunk = Math.max(1, chunking.transactionsPerChunk());
        int maxChars = Math.max(500, chunking.maxCharsPerChunk());
        List<StatementChunk> chunks = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String rec : records) {
            if (count > 0 && (count >= perChunk || sb.length() + rec.length() > maxChars)) {
                chunks.add(newChunk(chunks.size() + 1, count, sb));
                sb.setLength(0);
                count = 0;
            }
            sb.append(rec).append('\n');
            count++;
        }
        if (count > 0) chunks.add(newChunk(chunks.size() + 1, count, sb));
        return chunks;
    }

    private static StatementChunk newChunk(int index, int count, StringBuilder text) {
        return new StatementChunk("chunk_" + index, index, count, text.toString());
    }

    /**
     * PDF statements often spread one transaction over several lines. When date-led lines exist, continuation lines are
     * merged into the preceding record; otherwise (CSV/Excel rows, unknown layouts) every line is its own record.
     */
    private List<String> toRecords(List<String> lines) {
        boolean dateLed = lines.stream().anyMatch(l -> RECORD_START.matcher(l).find());
        if (!dateLed) return lines;
        List<String> records = new ArrayList<>();
        StringBuilder cur = null;
        for (String l : lines) {
            if (cur == null || RECORD_START.matcher(l).find()) {
                if (cur != null) records.add(cur.toString());
                cur = new StringBuilder(l);
            } else {
                cur.append(' ').append(l);
            }
        }
        if (cur != null) records.add(cur.toString());
        return records;
    }
}
