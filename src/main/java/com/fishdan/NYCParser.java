package com.fishdan;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStringsTable;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Consolidates precinct-level XLSX files into a single CSV while preserving the header only once.
 * Uses Apache POI's streaming XLSX handler to keep memory usage bounded.
 */
public final class NYCParser {

    private static final Path DEFAULT_DATA_DIR = Path.of("data");
    private static final String DEFAULT_OUTPUT_FILENAME = "combined_precincts.csv";

    static {
        // Allow POI to stream large files without tripping its default byte-array guard rails.
        IOUtils.setByteArrayMaxOverride(Integer.MAX_VALUE);
    }

    private NYCParser() {
        // Utility class
    }

    public static void main(String[] args) {
        Path dataDir = DEFAULT_DATA_DIR;
        if (!Files.isDirectory(dataDir)) {
            System.err.printf("Data directory not found: %s%n", dataDir.toAbsolutePath());
            System.exit(1);
        }

        Path output = args.length > 0 ? Path.of(args[0]) : dataDir.resolve(DEFAULT_OUTPUT_FILENAME);
        try {
            Files.createDirectories(Objects.requireNonNullElse(output.getParent(), Path.of(".")));
            mergeWorkbooks(dataDir, output);
            System.out.printf("Combined CSV written to %s%n", output.toAbsolutePath());
        } catch (IOException e) {
            System.err.printf("Failed to merge workbooks: %s%n", e.getMessage());
            System.exit(2);
        }
    }

    private static void mergeWorkbooks(Path dataDir, Path output) throws IOException {
        List<Path> workbooks = listWorkbookPaths(dataDir);
        if (workbooks.isEmpty()) {
            throw new IOException("No XLSX files found in " + dataDir.toAbsolutePath());
        }

        DataFormatter formatter = new DataFormatter();
        try (BufferedWriter writer = Files.newBufferedWriter(
                output,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {

            MergeContext context = new MergeContext(writer, formatter);
            for (Path workbookPath : workbooks) {
                processWorkbook(workbookPath, context);
            }
        }
    }

    private static void processWorkbook(Path workbookPath, MergeContext context) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(workbookPath.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            SharedStringsTable sharedStrings = createSharedStrings(pkg);

            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            if (!sheets.hasNext()) {
                System.err.printf("Skipping %s (no sheets)%n", workbookPath.getFileName());
                return;
            }

            try (InputStream sheetStream = sheets.next()) {
                XMLReader parser = SAXHelper.newXMLReader();
                StreamingSheetHandler handler =
                        new StreamingSheetHandler(workbookPath.getFileName().toString(), context);
                parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, sharedStrings, handler,
                        context.formatter, false));
                parser.parse(new InputSource(sheetStream));
            }
        } catch (OpenXML4JException | SAXException e) {
            throw new IOException("Error processing " + workbookPath.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static SharedStringsTable createSharedStrings(OPCPackage pkg) throws IOException {
        try {
            return new ReadOnlySharedStringsTable(pkg);
        } catch (SAXException e) {
            throw new IOException("Failed to initialize shared strings table: " + e.getMessage(), e);
        }
    }

    private static List<Path> listWorkbookPaths(Path dataDir) throws IOException {
        try (Stream<Path> stream = Files.list(dataDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".xlsx"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private static void writeCsvRow(BufferedWriter writer, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(escape(values.get(i)));
        }
        writer.write('\n');
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needsQuotes ? "\"" + escaped + "\"" : escaped;
    }

    private static void trimTrailingEmpties(List<String> values) {
        int lastNonEmpty = values.size() - 1;
        while (lastNonEmpty >= 0 && values.get(lastNonEmpty).isEmpty()) {
            lastNonEmpty--;
        }
        if (lastNonEmpty + 1 < values.size()) {
            values.subList(lastNonEmpty + 1, values.size()).clear();
        }
    }

    private static boolean headersMatch(List<String> canonical, List<String> other) {
        int size = Math.max(canonical.size(), other.size());
        for (int i = 0; i < size; i++) {
            String left = i < canonical.size() ? canonical.get(i) : "";
            String right = i < other.size() ? other.get(i) : "";
            if (!Objects.equals(left, right)) {
                return false;
            }
        }
        return true;
    }

    private static int columnIndexFromReference(String cellReference, int nextColumnIndex) {
        if (cellReference == null) {
            return nextColumnIndex;
        }
        int col = 0;
        for (int i = 0; i < cellReference.length(); i++) {
            char ch = cellReference.charAt(i);
            if (Character.isLetter(ch)) {
                col = (col * 26) + (Character.toUpperCase(ch) - 'A' + 1);
            } else {
                break;
            }
        }
        return Math.max(col - 1, 0);
    }

    private static final class MergeContext {
        private final BufferedWriter writer;
        private final DataFormatter formatter;
        private List<String> header;
        private int columnCount;
        private long recordsWritten;

        private MergeContext(BufferedWriter writer, DataFormatter formatter) {
            this.writer = writer;
            this.formatter = formatter;
            this.columnCount = -1;
            this.recordsWritten = 0L;
        }
    }

    private static final class StreamingSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final String workbookName;
        private final MergeContext context;
        private List<String> currentRowValues;

        private StreamingSheetHandler(String workbookName, MergeContext context) {
            this.workbookName = workbookName;
            this.context = context;
        }

        @Override
        public void startRow(int rowNum) {
            currentRowValues = new ArrayList<>();
        }

        @Override
        public void endRow(int rowNum) {
            trimTrailingEmpties(currentRowValues);
            try {
                if (rowNum == 0) {
                    handleHeaderRow();
                } else {
                    handleDataRow();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int targetIndex = columnIndexFromReference(cellReference, currentRowValues.size());
            while (currentRowValues.size() < targetIndex) {
                currentRowValues.add("");
            }
            String value = formattedValue;
            if (value == null) {
                value = "";
            }
            currentRowValues.add(value);
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // Unused for CSV export.
        }

        private void handleHeaderRow() throws IOException {
            if (currentRowValues.isEmpty()) {
                System.err.printf("Skipping header in %s (empty header row)%n", workbookName);
                return;
            }

            if (context.header == null) {
                context.header = new ArrayList<>(currentRowValues);
                context.columnCount = context.header.size();
                writeCsvRow(context.writer, context.header);
            } else if (!headersMatch(context.header, currentRowValues)) {
                System.err.printf("Header mismatch detected in %s. Proceeding with canonical header.%n", workbookName);
            }
        }

        private void handleDataRow() throws IOException {
            if (currentRowValues.stream().allMatch(String::isEmpty)) {
                return;
            }

            if (context.columnCount < 0) {
                context.columnCount = currentRowValues.size();
            }

            while (currentRowValues.size() < context.columnCount) {
                currentRowValues.add("");
            }
            if (currentRowValues.size() > context.columnCount) {
                context.columnCount = currentRowValues.size();
            }

            writeCsvRow(context.writer, currentRowValues);
            context.recordsWritten++;
            if (context.recordsWritten % 5000 == 0) {
                System.out.printf("Total records written: %,d%n", context.recordsWritten);
            }
        }
    }
}
