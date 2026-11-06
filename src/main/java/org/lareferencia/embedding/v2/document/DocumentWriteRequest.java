package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description="PATCH updates the supplied fields; PUT requires metadata and texts and replaces the full snapshot.")
public class DocumentWriteRequest {
  private JsonNode metadata;
  private List<SourceTextInput> texts;
  private boolean metadataPresent;
  private boolean textsPresent;

  @JsonSetter("metadata")
  @Schema(description="JSON object; PATCH replaces it when supplied")
  public void setMetadata(JsonNode value) { metadataPresent=true; metadata=value; }
  @JsonSetter("texts")
  @Schema(description="Complete text objects keyed by text_id; PATCH preserves omitted keys")
  public void setTexts(List<SourceTextInput> value) { textsPresent=true; texts=value; }
  public boolean hasMetadata() { return metadataPresent; }
  public boolean hasTexts() { return textsPresent; }
  public JsonNode metadata() { return metadata; }
  public List<SourceTextInput> texts() { return texts; }
}
