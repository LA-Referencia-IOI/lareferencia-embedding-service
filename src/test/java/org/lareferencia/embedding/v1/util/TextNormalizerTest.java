package org.lareferencia.embedding.v1.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void testBasicNormalization() {
        String input = "  este   es un texto\n\nacadémico  ";
        String expected = "este es un texto académico";
        assertEquals(expected, normalizer.normalize(input));
    }

    @Test
    void testWhitespaceCollapsing() {
        assertEquals("a b c", normalizer.normalize("a    b  c"));
        assertEquals("a b c", normalizer.normalize("  a b c  "));
    }

    @Test
    void testLineBreakReplacement() {
        assertEquals("a b c", normalizer.normalize("a\nb\r\nc"));
        assertEquals("a b", normalizer.normalize("a\n\n\nb"));
    }

    @Test
    void testCase() {
        assertEquals("texto con mayúsculas", normalizer.normalize("  Texto con Mayúsculas  "));
    }

    @Test
    void testAccentsAndUnicode() {
        // NFC normalization check (though subtle to show here without raw hex)
        assertEquals("áéíóú ñ ñ", normalizer.normalize("áéíóú ñ Ñ"));
    }

    @Test
    void testNullAndEmpty() {
        assertEquals("", normalizer.normalize(null));
        assertEquals("", normalizer.normalize("   "));
        assertEquals("", normalizer.normalize(""));
    }
}
