package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;

@Schema(
    description = "PATCH updates the supplied fields; PUT requires metadata and texts and replaces the full snapshot.")
public class DocumentWriteRequest {
  private JsonNode metadata;
  private List<SourceTextInput> texts;
  private boolean metadataPresent;
  private boolean textsPresent;

  @JsonSetter("metadata")
  @Schema(description = "JSON object; PATCH replaces it when supplied")
  public void setMetadata(JsonNode value) {
    metadataPresent = true;
    metadata = value;
  }

  @JsonSetter("texts")
  @Schema(description = "Text objects use knd, optional lng, and cnt; PATCH preserves omitted texts")
  public void setTexts(List<SourceTextInput> value) {
    textsPresent = true;
    if (value == null) {
      texts = null;
      return;
    }
    var occurrences = new HashMap<String, Integer>();
    var result = new ArrayList<SourceTextInput>(value.size());
    for (var text : value) {
      if (text == null || text.key() != null || text.kind() == null) {
        result.add(text);
        continue;
      }
      String group = hash(text.kind() + "\0" + (text.language() == null ? "\0" : text.language()));
      int ordinal = occurrences.merge(group, 1, Integer::sum) - 1;
      result.add(new SourceTextInput("import:" + group + ":" + ordinal, text.kind(), text.language(), text.content()));
    }
    texts = result;
  }

  static String textKey(String kind, String language, int ordinal) {
    return "import:" + hash(kind + "\0" + (language == null ? "\0" : language)) + ":" + ordinal;
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public boolean hasMetadata() {
    return metadataPresent;
  }

  public boolean hasTexts() {
    return textsPresent;
  }

  public JsonNode metadata() {
    return metadata;
  }

  public List<SourceTextInput> texts() {
    return texts;
  }
}
