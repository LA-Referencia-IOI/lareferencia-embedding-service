package org.lareferencia.embedding.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void testBasicNormalization() {
        String input = "  Este   es un texto\n\nacadémico  ";
        String expected = "Este es un texto académico";
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
    void testCasePreservation() {
        assertEquals("Texto con Mayúsculas", normalizer.normalize("  Texto con Mayúsculas  "));
    }

    @Test
    void testAccentsAndUnicode() {
        // NFC normalization check (though subtle to show here without raw hex)
        assertEquals("áéíóú ñ Ñ", normalizer.normalize("áéíóú ñ Ñ"));
    }

    @Test
    void testNullAndEmpty() {
        assertNull(normalizer.normalize(null));
        assertEquals("", normalizer.normalize("   "));
        assertEquals("", normalizer.normalize(""));
    }
}
