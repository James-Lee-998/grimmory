package org.booklore.service.virtualbook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.booklore.config.AppProperties;
import org.booklore.model.enums.Provider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Seeds virtual books from a Project Gutenberg catalog CSV on startup.
 * <p>
 * Reads Gutenberg's official catalog ({@code https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv.gz}), header
 * {@code Text#,Type,Issued,Title,Language,Authors,Subjects,LoCC,Bookshelves}. Only {@code Type=Text} rows are used.
 * Each ebook becomes Gutenberg mirrors on the virtual book with the matching {@link CanonicalBookId}; the book is
 * created if no provider has added it yet. Ebook numbers already present as Gutenberg mirrors are skipped, so
 * restarts are cheap and a newer extract only adds new ebooks. Existing rows are never modified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GutenbergCatalogSeeder {

    private static final int BATCH_SIZE = 1000;
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_AUTHORS_LENGTH = 500;
    private static final List<String> REQUIRED_COLUMNS = List.of("Text#", "Type", "Issued", "Title", "Language", "Authors");
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\R+\\s*");
    private static final Pattern ROLE = Pattern.compile("\\s*\\[[^]]*]");
    private static final Pattern PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)");
    // Life dates and periods: "1847-1910", "1957-", "621? BCE-565? BCE", "fl. 1250", "active 17th century".
    private static final Pattern LIFE_DATES = Pattern.compile("\\d|(?i)\\b(bce|fl\\.|active|century)\\b");
    private static final Pattern NAME_SUFFIX = Pattern.compile("(?i)(jr|sr)\\.?|[ivx]+");

    private static final String EXISTING_EBOOKS_SQL =
            "SELECT DISTINCT external_id FROM virtual_book_mirrors WHERE provider = 'GUTENBERG'";

    // A book another ebook or provider already created keeps its metadata (no-op update).
    private static final String INSERT_BOOK_SQL = """
            INSERT INTO virtual_books (virtual_book_id, title, authors, language, issued_date, is_downloaded, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, FALSE, ?, ?)
            ON DUPLICATE KEY UPDATE virtual_book_id = virtual_book_id
            """;

    private static final String BOOK_IDS_SQL = "SELECT virtual_book_id, id FROM virtual_books WHERE virtual_book_id IN (:ids)";

    // Plain VALUES rather than INSERT ... SELECT: MariaDB's bulk batch protocol rejects INSERT ... SELECT with
    // "This command is not supported in the prepared statement protocol yet".
    private static final String INSERT_MIRROR_SQL = """
            INSERT INTO virtual_book_mirrors (virtual_book_ref_id, provider, external_id, download_url, file_type, quality_score)
            VALUES (?, 'GUTENBERG', ?, ?, ?, ?)
            """;

    // One mirror per Gutenberg download format, best first. Gutenberg redirects these to its file cache.
    private static final List<GutenbergFormat> FORMATS = List.of(
            new GutenbergFormat(".epub3.images", "EPUB", 100),
            new GutenbergFormat(".epub.noimages", "EPUB", 60),
            new GutenbergFormat(".kf8.images", "AZW3", 50)
    );

    private final AppProperties appProperties;
    private final ResourceLoader resourceLoader;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        AppProperties.VirtualBookSeed config = appProperties.getVirtualBookSeed();
        if (config == null || !config.isEnabled()) {
            log.info("Virtual book seeding disabled");
            return;
        }

        Resource resource = resourceLoader.getResource(config.getLocation());
        if (!resource.exists()) {
            log.warn("Virtual book seed file not found at {}, skipping", config.getLocation());
            return;
        }

        try {
            long start = System.currentTimeMillis();
            int inserted = seed(resource);
            log.info("Virtual book seeding finished: {} new Gutenberg ebooks from {} in {} ms",
                    inserted, config.getLocation(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Virtual book seeding from {} failed", config.getLocation(), e);
        }
    }

    int seed(Resource resource) throws IOException {
        Set<String> existingEbooks = new HashSet<>(jdbcTemplate.queryForList(EXISTING_EBOOKS_SQL, String.class));
        log.info("Seeding virtual books from {} ({} Gutenberg ebooks already present)", resource.getDescription(), existingEbooks.size());

        int[] inserted = {0};
        List<GutenbergRow> batch = new ArrayList<>(BATCH_SIZE);

        int skipped = readRows(resource, row -> {
            if (!existingEbooks.add(String.valueOf(row.ebookNo()))) {
                return;
            }
            batch.add(row);
            if (batch.size() == BATCH_SIZE) {
                inserted[0] += insertBatch(batch);
                batch.clear();
            }
        });

        if (!batch.isEmpty()) {
            inserted[0] += insertBatch(batch);
        }
        if (skipped > 0) {
            log.info("Skipped {} unusable rows in seed file", skipped);
        }
        return inserted[0];
    }

    /** Parses the catalog, passing each usable row to {@code consumer}. Returns the number of unusable rows. */
    static int readRows(Resource resource, Consumer<GutenbergRow> consumer) throws IOException {
        int skipped = 0;
        try (Reader reader = openReader(resource);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .get()
                     .parse(reader)) {

            if (!parser.getHeaderMap().keySet().containsAll(REQUIRED_COLUMNS)) {
                throw new IOException("Not a Gutenberg catalog CSV (pg_catalog.csv): expected columns " + REQUIRED_COLUMNS
                        + " but found " + parser.getHeaderNames());
            }
            for (CSVRecord record : parser) {
                GutenbergRow row = toRow(record);
                if (row == null) {
                    skipped++;
                } else {
                    consumer.accept(row);
                }
            }
        }
        return skipped;
    }

    private int insertBatch(List<GutenbergRow> rows) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        return transactionTemplate.execute(status -> {
            jdbcTemplate.batchUpdate(INSERT_BOOK_SQL, rows, rows.size(), (ps, row) -> {
                ps.setLong(1, row.canonicalId());
                ps.setString(2, row.title());
                ps.setString(3, row.authors());
                ps.setString(4, row.language());
                ps.setString(5, row.issuedDate());
                ps.setTimestamp(6, now);
                ps.setTimestamp(7, now);
            });
            Map<Long, Long> rowIdByCanonicalId = new HashMap<>();
            List<Long> canonicalIds = rows.stream().map(GutenbergRow::canonicalId).distinct().toList();
            namedJdbcTemplate.query(BOOK_IDS_SQL, new MapSqlParameterSource("ids", canonicalIds),
                    rs -> {
                        rowIdByCanonicalId.put(rs.getLong("virtual_book_id"), rs.getLong("id"));
                    });

            List<Object[]> mirrors = new ArrayList<>(rows.size() * FORMATS.size());
            for (GutenbergRow row : rows) {
                Long rowId = rowIdByCanonicalId.get(row.canonicalId());
                String ebookNo = String.valueOf(row.ebookNo());
                for (GutenbergFormat format : FORMATS) {
                    mirrors.add(new Object[]{rowId, ebookNo,
                            "https://www.gutenberg.org/ebooks/" + ebookNo + format.suffix(), format.fileType(), format.score()});
                }
            }
            jdbcTemplate.batchUpdate(INSERT_MIRROR_SQL, mirrors);
            return rows.size();
        });
    }

    /** Returns null for rows that are not usable text ebooks (audio, images, datasets, malformed rows). */
    private static GutenbergRow toRow(CSVRecord record) {
        Long ebookNo = parseLong(field(record, "Text#"));
        String title = toTitle(field(record, "Title"));
        if (ebookNo == null || title == null || !"Text".equals(field(record, "Type"))) {
            return null;
        }
        String authors = toAuthors(field(record, "Authors"));
        String language = toLanguageCode(field(record, "Language"));
        // Full title including subtitle: Gutenberg often puts the volume or the specific work there.
        long canonicalId = CanonicalBookId.of(title, authors, language, Provider.GUTENBERG, String.valueOf(ebookNo));
        return new GutenbergRow(
                ebookNo,
                canonicalId,
                truncate(title, MAX_TITLE_LENGTH),
                truncate(authors, MAX_AUTHORS_LENGTH),
                language,
                toIssuedDate(field(record, "Issued"))
        );
    }

    /** The catalog puts the subtitle on its own line: "Title\nSubtitle" becomes "Title: Subtitle". */
    static String toTitle(String title) {
        if (title == null) {
            return null;
        }
        String joined = LINE_BREAKS.matcher(title.trim()).replaceAll(": ");
        return joined.isEmpty() ? null : joined;
    }

    /**
     * Turns the catalog's "Mikszáth, Kálmán, 1847-1910; Kivimäki, Toini, 1894-1980 [Translator]" into
     * "Kálmán Mikszáth": contributors with a role are dropped when there is a main author, life dates and
     * parenthetical expansions are removed, and "Last, First" becomes "First Last". Authors are joined with "; ".
     */
    static String toAuthors(String raw) {
        if (raw == null) {
            return null;
        }
        List<String> entries = Arrays.stream(raw.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> mainAuthors = entries.stream().filter(entry -> !ROLE.matcher(entry).find()).toList();
        String authors = (mainAuthors.isEmpty() ? entries : mainAuthors).stream()
                .map(GutenbergCatalogSeeder::toDisplayName)
                .filter(name -> !name.isEmpty())
                .distinct()
                .collect(Collectors.joining("; "));
        return authors.isEmpty() ? null : authors;
    }

    static String toDisplayName(String entry) {
        String name = ROLE.matcher(entry).replaceAll("");
        name = PARENTHETICAL.matcher(name).replaceAll("");
        List<String> parts = new ArrayList<>(Arrays.stream(name.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        while (parts.size() > 1 && LIFE_DATES.matcher(parts.getLast()).find()) {
            parts.removeLast();
        }
        if (parts.size() == 2) {
            return parts.get(1) + " " + parts.get(0);
        }
        if (parts.size() == 3 && NAME_SUFFIX.matcher(parts.get(2)).matches()) {
            return parts.get(1) + " " + parts.get(0) + " " + parts.get(2);
        }
        return String.join(", ", parts);
    }

    /** First language of "en; fr". Only two-letter codes fit the column; others (e.g. "grc") become null. */
    static String toLanguageCode(String language) {
        if (language == null) {
            return null;
        }
        String first = language.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return first.matches("[a-z]{2}") ? first : null;
    }

    /** The catalog's "Issued" is Gutenberg's release date in ISO format. */
    static String toIssuedDate(String issued) {
        if (issued == null) {
            return null;
        }
        try {
            return LocalDate.parse(issued).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String field(CSVRecord record, String name) {
        if (!record.isMapped(name) || !record.isSet(name)) {
            return null;
        }
        String value = record.get(name).trim();
        return value.isEmpty() ? null : value;
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim();
    }

    private static Reader openReader(Resource resource) throws IOException {
        InputStream in = resource.getInputStream();
        String name = resource.getFilename();
        if (name != null && name.endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        // Skip a UTF-8 byte order mark, otherwise the first header gets the BOM prepended.
        reader.mark(1);
        if (reader.read() != '﻿') {
            reader.reset();
        }
        return reader;
    }

    private record GutenbergFormat(String suffix, String fileType, int score) {
    }

    record GutenbergRow(long ebookNo, long canonicalId, String title, String authors, String language, String issuedDate) {
    }
}
