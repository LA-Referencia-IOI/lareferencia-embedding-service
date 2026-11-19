package org.lareferencia.embedding.v2.preparation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Assigns stable document-local identities without retaining a source text reference. */
final class DocumentUnitIdentity {
  private final Map<String, Integer> occurrences = new HashMap<>();

  String next(String preparedInput) {
    String digest;
    try {
      digest =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(preparedInput.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    int ordinal = occurrences.merge(digest, 1, Integer::sum) - 1;
    return digest + ":" + ordinal;
  }
}
