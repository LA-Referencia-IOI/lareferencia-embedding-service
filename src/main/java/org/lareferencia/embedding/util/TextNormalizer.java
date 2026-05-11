package org.lareferencia.embedding.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Utility component for text normalization.
 * Version: v1
 */
@Component
public class TextNormalizer {

    public static final String VERSION = "v1";

    // Precompiling patterns saves CPU on bulk processing
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\n\\r]+");
    private static final Pattern MULTIPLE_SPACES = Pattern.compile("\\s+");

    public String normalize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        // 1. Quick initial cleanup (Reduces String size for heavy steps)
        String normalized = input.trim();

        // 2. Replace line breaks with spaces
        normalized = LINE_BREAKS.matcher(normalized).replaceAll(" ");

        // 3. Collapse multiple spaces
        normalized = MULTIPLE_SPACES.matcher(normalized).replaceAll(" ");

        // 4. Unicode Normalization (NFC) - Guarantees "ç" vs "c + ̧"
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFC);

        // 5. Deterministic case-insensitive
        return normalized.toLowerCase(Locale.ROOT);
    }
}
