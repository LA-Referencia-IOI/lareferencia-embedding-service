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
    assertThrows(V2Exception.class, () -> validation.externalId("invalid\u0000key"));
    assertThrows(V2Exception.class, () -> validation.externalId("\ud800"));
  }

  @Test void putRequiresBothFieldsAndPatchRejectsDuplicateKeys() {
    var request = new DocumentWriteRequest();
    request.setMetadata(mapper.createObjectNode());
    assertDoesNotThrow(() -> validation.write(request, false));
    assertEquals(400, assertThrows(V2Exception.class, () -> validation.write(request, true)).status());
    var text = new SourceTextInput("same", "abstract", "en", "content");
    request.setTexts(List.of(text, text));
    assertEquals("duplicate_text", assertThrows(V2Exception.class, () -> validation.write(request, false)).code());
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

  @Test void compactTextsGetStableImportCompatibleKeys() throws Exception {
    String json="{\"texts\":[{\"knd\":\"title\",\"lng\":\"pt\",\"cnt\":\"Acesso aberto\"},{\"knd\":\"title\",\"lng\":\"pt\",\"cnt\":\"Outro título\"}]}";
    var first=mapper.readValue(json,DocumentWriteRequest.class);
    var second=mapper.readValue(json.replace("Acesso aberto","Alterado"),DocumentWriteRequest.class);
    validation.write(first,false);
    assertEquals(first.texts().get(0).key(),second.texts().get(0).key());
    assertNotEquals(first.texts().get(0).key(),first.texts().get(1).key());
    String group=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest("title\0pt".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertEquals("import:"+group+":0",first.texts().get(0).key());
    var output=mapper.valueToTree(new SourceTextResource("title","pt","Acesso aberto",1,"hash",null));
    assertEquals("title",output.path("knd").asText());
    assertEquals("pt",output.path("lng").asText());
    assertEquals("Acesso aberto",output.path("cnt").asText());
    assertFalse(output.has("text_id"));
  }
}
