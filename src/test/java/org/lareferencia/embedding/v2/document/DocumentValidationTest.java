package org.lareferencia.embedding.v2.document;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;

class DocumentValidationTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final DvpProperties properties = new DvpProperties();
  private final DocumentValidation validation = new DocumentValidation(properties, mapper);

  @Test void identifiersCountUnicodeCodePointsAndRejectInvalidUnicode() {
    assertDoesNotThrow(() -> validation.externalId("😀".repeat(512)));
    assertEquals(400, assertThrows(V2Exception.class, () -> validation.externalId("😀".repeat(513))).status());
    assertThrows(V2Exception.class, () -> validation.textId("invalid\u0000key"));
    assertThrows(V2Exception.class, () -> validation.textId("\ud800"));
  }

  @Test void putRequiresBothFieldsAndPatchRejectsDuplicateKeys() {
    var request = new DocumentWriteRequest();
    request.setMetadata(mapper.createObjectNode());
    assertDoesNotThrow(() -> validation.write(request, false));
    assertEquals(400, assertThrows(V2Exception.class, () -> validation.write(request, true)).status());
    var text = new SourceTextInput("same", "abstract", "en", "content");
    request.setTexts(List.of(text, text));
    assertEquals("duplicate_text_id", assertThrows(V2Exception.class, () -> validation.write(request, false)).code());
  }

  @Test void mergedSnapshotCannotBypassTextCountOrByteLimit() {
    properties.setMaxTexts(1);
    var text = new SourceTextInput("one", "title", null, "content");
    assertEquals("too_many_texts", assertThrows(V2Exception.class,
        () -> validation.snapshot(mapper.createObjectNode(), List.of(text, text))).code());
    properties.setMaxDocumentBytes(10);
    assertEquals("document_too_large", assertThrows(V2Exception.class,
        () -> validation.snapshot(mapper.createObjectNode(), List.of(text))).code());
  }
}
