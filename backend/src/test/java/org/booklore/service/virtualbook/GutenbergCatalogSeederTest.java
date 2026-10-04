package org.booklore.service.virtualbook;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GutenbergCatalogSeederTest {

    private static final String HEADER = "Text#,Type,Issued,Title,Language,Authors,Subjects,LoCC,Bookshelves\n";

    @Test
    void parsesTextRowsAndSkipsOtherTypes() throws Exception {
        String csv = HEADER
                + "42379,Text,2013-03-20,Öreg szekér fakó hám: Újabb elbeszélések,hu,\"Mikszáth, Kálmán, 1847-1910\",Short stories,PH,\n"
                + "2,Text,1972-12-01,\"The United States Bill of Rights\nThe Ten Original Amendments\",en,United States,Civil rights,JK,Politics\n"
                + "9273,Sound,2003-10-01,A Modest Proposal,en,\"Swift, Jonathan, 1667-1745\",Satire,PR,\n"
                + "not-a-number,Text,2000-01-01,Broken,en,,,,\n";

        List<GutenbergCatalogSeeder.GutenbergRow> rows = new ArrayList<>();
        int skipped = GutenbergCatalogSeeder.readRows(new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)), rows::add);

        assertEquals(2, skipped);
        assertEquals(2, rows.size());

        GutenbergCatalogSeeder.GutenbergRow mikszath = rows.get(0);
        assertEquals(42379L, mikszath.ebookNo());
        assertEquals("Öreg szekér fakó hám: Újabb elbeszélések", mikszath.title());
        assertEquals("Kálmán Mikszáth", mikszath.authors());
        assertEquals("hu", mikszath.language());
        assertEquals("2013-03-20", mikszath.issuedDate());

        assertEquals("The United States Bill of Rights: The Ten Original Amendments", rows.get(1).title());
        assertEquals("United States", rows.get(1).authors());
    }

    @Test
    void rejectsFilesInAnotherFormat() {
        String oldExtract = "ebook_no,title,author,language\n1,Title,Author,English\n";

        assertThrows(IOException.class, () -> GutenbergCatalogSeeder.readRows(
                new ByteArrayResource(oldExtract.getBytes(StandardCharsets.UTF_8)), row -> {}));
    }

    @Test
    void cleansAuthorNames() {
        assertEquals("Kálmán Mikszáth", GutenbergCatalogSeeder.toAuthors("Mikszáth, Kálmán, 1847-1910; Kivimäki, Toini, 1894-1980 [Translator]"));
        assertEquals("Aesop", GutenbergCatalogSeeder.toAuthors("Aesop, 621? BCE-565? BCE; Townsend, George Fyler, 1814-1900 [Translator]"));
        assertEquals("Eric S. Raymond; Guy L. Steele", GutenbergCatalogSeeder.toAuthors("Raymond, Eric S., 1957- [Editor]; Steele, Guy L., 1954- [Editor]"));
        assertEquals("Martin Luther King Jr.", GutenbergCatalogSeeder.toAuthors("King, Martin Luther, Jr., 1929-1968"));
        assertEquals("Mary E. Ropes", GutenbergCatalogSeeder.toAuthors("Ropes, Mary E. (Mary Emily), 1842-1932"));
        assertNull(GutenbergCatalogSeeder.toAuthors(null));
    }

    @Test
    void mapsLanguagesAndDates() {
        assertEquals("en", GutenbergCatalogSeeder.toLanguageCode("en"));
        assertEquals("de", GutenbergCatalogSeeder.toLanguageCode("de; en"));
        assertNull(GutenbergCatalogSeeder.toLanguageCode("grc"));
        assertNull(GutenbergCatalogSeeder.toLanguageCode(null));

        assertEquals("1971-12-01", GutenbergCatalogSeeder.toIssuedDate("1971-12-01"));
        assertNull(GutenbergCatalogSeeder.toIssuedDate("sometime"));
    }

    @Test
    void parsesBundledCatalog() throws Exception {
        Map<Long, GutenbergCatalogSeeder.GutenbergRow> rows = new HashMap<>();
        int skipped = GutenbergCatalogSeeder.readRows(new ClassPathResource("seed/gutenberg-catalog.csv.gz"),
                row -> rows.put(row.ebookNo(), row));

        assertTrue(rows.size() > 75_000, "expected the full catalog, got " + rows.size());

        GutenbergCatalogSeeder.GutenbergRow mikszath = rows.get(42379L);
        assertNotNull(mikszath);
        assertEquals("Kálmán Mikszáth", mikszath.authors());
        assertEquals("hu", mikszath.language());
        rows.values().forEach(row -> assertTrue(row.title().length() <= 255));
        rows.values().forEach(row -> assertTrue(row.language() == null || row.language().length() == 2));

        long withReplacementChars = rows.values().stream()
                .filter(row -> (row.title() + row.authors()).contains("�")).count();
        long distinctBooks = rows.values().stream().mapToLong(GutenbergCatalogSeeder.GutenbergRow::canonicalId).distinct().count();
        System.out.printf("Gutenberg catalog: %d text ebooks -> %d canonical virtual books (%d non-text/unusable rows skipped, %d with replacement chars)%n",
                rows.size(), distinctBooks, skipped, withReplacementChars);
        assertEquals(0, withReplacementChars);
    }
}
