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
    private static final Pattern HYPHENATED_LINE_BREAK = Pattern.compile("-\\s*\\n\\s*");

    public String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String normalized = input.trim();

        // Join words split by hyphen + line break
        // Example: "multi-\nlingual" -> "multilingual"
        normalized = HYPHENATED_LINE_BREAK.matcher(normalized).replaceAll("");

        // Lowercase normalization
        normalized = normalized.toLowerCase(Locale.ROOT);

        // Replace remaining line breaks with spaces
        normalized = LINE_BREAKS.matcher(normalized).replaceAll(" ");

        // Collapse multiple spaces/tabs/newlines
        normalized = MULTIPLE_SPACES.matcher(normalized).replaceAll(" ");

        // Unicode normalization (ensures canonical representation)
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFC);

        return normalized;
    }
}
