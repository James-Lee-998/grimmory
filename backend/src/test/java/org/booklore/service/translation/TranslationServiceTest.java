package org.booklore.service.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TranslationServiceTest {

    @Test
    void mapsMetadataLanguagesToLibreTranslateCodes() {
        assertEquals("en", TranslationService.toLanguageCode("en"));
        assertEquals("en", TranslationService.toLanguageCode("en-US"));
        assertEquals("en", TranslationService.toLanguageCode("en_GB"));
        assertEquals("de", TranslationService.toLanguageCode("deu"));
        assertEquals("de", TranslationService.toLanguageCode("ger"));
        assertEquals("fr", TranslationService.toLanguageCode("fre"));
        assertEquals("fr", TranslationService.toLanguageCode("French"));
        assertEquals("hu", TranslationService.toLanguageCode("Hungarian"));
        assertNull(TranslationService.toLanguageCode(""));
        assertNull(TranslationService.toLanguageCode(null));
        assertNull(TranslationService.toLanguageCode("Klingon"));
    }

    @Test
    void distinguishesSimplifiedAndTraditionalChinese() {
        assertEquals("zh", TranslationService.toLanguageCode("zh"));
        assertEquals("zh", TranslationService.toLanguageCode("zh-CN"));
        assertEquals("zh", TranslationService.toLanguageCode("zh-Hans"));
        assertEquals("zh", TranslationService.toLanguageCode("chi"));
        assertEquals("zh", TranslationService.toLanguageCode("Chinese"));
        assertEquals("zh", TranslationService.toLanguageCode("Mandarin"));
        assertEquals("zt", TranslationService.toLanguageCode("zh-Hant"));
        assertEquals("zt", TranslationService.toLanguageCode("zh-TW"));
        assertEquals("zt", TranslationService.toLanguageCode("Chinese (Traditional)"));
    }
}
