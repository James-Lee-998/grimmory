package org.booklore.service.virtualbook;

import org.booklore.model.enums.Provider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalBookIdTest {

    @Test
    void sameBookFromDifferentProvidersGetsSameId() {
        long gutenberg = CanonicalBookId.of("The Mountain Man", "Robert J. Horton", "en", Provider.GUTENBERG, "79704");
        long standardEbooks = CanonicalBookId.of("Mountain Man", "Horton, Robert J.", "en", Provider.STANDARD_EBOOKS, "robert-j-horton/the-mountain-man");

        assertEquals(gutenberg, standardEbooks);
    }

    @Test
    void ignoresCaseAccentsPunctuationAndNameExpansions() {
        assertEquals(
                CanonicalBookId.of("Les Misérables", "Victor Hugo", "fr", Provider.GUTENBERG, "1"),
                CanonicalBookId.of("LES MISERABLES!", "Hugo, Victor", "fr", Provider.INTERNET_ARCHIVE, "x"));
        assertEquals(
                CanonicalBookId.of("The mystery of Hoyle's Mouth", "Mary E. (Mary Emily) Ropes", "en", Provider.GUTENBERG, "79697"),
                CanonicalBookId.of("Mystery of Hoyles Mouth", "Ropes, Mary E.", "en", Provider.FADED_PAGE, "y"));
    }

    @Test
    void keepsTranslationsAndDifferentAuthorsApart() {
        long french = CanonicalBookId.of("Les Misérables", "Victor Hugo", "fr", Provider.GUTENBERG, "1");
        long english = CanonicalBookId.of("Les Misérables", "Victor Hugo", "en", Provider.GUTENBERG, "2");
        long otherAuthor = CanonicalBookId.of("Les Misérables", "Someone Else", "fr", Provider.GUTENBERG, "3");

        assertNotEquals(french, english);
        assertNotEquals(french, otherAuthor);
    }

    @Test
    void keepsVolumesAndSubtitledWorksApart() {
        assertNotEquals(
                CanonicalBookId.of("Histoire de France - Moyen âge; (Vol. 3/10)", "Jules Michelet", "fr", Provider.GUTENBERG, "41992"),
                CanonicalBookId.of("Histoire de France - Moyen âge; (Vol. 4 / 10)", "Jules Michelet", "fr", Provider.GUTENBERG, "42021"));
        assertNotEquals(
                CanonicalBookId.of("De Werken van William Shakespeare: Koning Richard de Tweede", "William Shakespeare", "nl", Provider.GUTENBERG, "63368"),
                CanonicalBookId.of("De Werken van William Shakespeare: Koning Hendrik de Vierde", "William Shakespeare", "nl", Provider.GUTENBERG, "63367"));
    }

    @Test
    void doesNotMergeAuthorlessBooks() {
        long first = CanonicalBookId.of("Poems", null, "en", Provider.GUTENBERG, "100");
        long second = CanonicalBookId.of("Poems", "  ", "en", Provider.GUTENBERG, "200");

        assertNotEquals(first, second);
        assertEquals(first, CanonicalBookId.of("Poems", null, "en", Provider.GUTENBERG, "100"));
    }

    @Test
    void multipleAuthorsAreOrderIndependentAndIdIsPositive() {
        long id = CanonicalBookId.of("Good Omens", "Terry Pratchett; Neil Gaiman", "en", Provider.GUTENBERG, "1");

        assertEquals(id, CanonicalBookId.of("Good Omens", "Gaiman, Neil; Pratchett, Terry", "en", Provider.GUTENBERG, "2"));
        assertTrue(id >= 0);
    }
}
