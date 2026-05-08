package org.lareferencia.embedding.util;

import org.springframework.stereotype.Component;

import java.text.Normalizer;

/**
 * Utility component for text normalization.
 * Version: v1
 */
@Component
public class TextNormalizer {

    public static final String VERSION = "v1";

    /**
     * Normalizes the input text according to version v1 rules:
     * - Trim leading and trailing whitespace.
     * - Normalize Unicode characters (NFC).
     * - Collapse multiple spaces into one.
     * - Replace repeated line breaks with a single space.
     * - Preserve case.
     * - Preserve accents and diacritics.
     *
     * @param input the raw text to normalize
     * @return the normalized text
     */
    public String normalize(String input) {
        if (input == null) {
            return null;
        }

        // TODO: Do we normalize case-sensitive values to lowercase?

        // 1. Unicode Normalization (NFC)
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);

        // 2. Replace all line breaks and carriage returns with a single space
        normalized = normalized.replaceAll("[\\n\\r]+", " ");

        // 3. Collapse multiple spaces (and other whitespace) into a single space
        normalized = normalized.replaceAll("\\s+", " ");

        // 4. Trim leading and trailing whitespace
        return normalized.trim();
    }
}
