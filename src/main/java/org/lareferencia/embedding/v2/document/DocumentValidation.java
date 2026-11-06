package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.springframework.stereotype.Component;

@Component
public class DocumentValidation {
  private final DvpProperties properties;
  private final ObjectMapper mapper;
  public DocumentValidation(DvpProperties properties,ObjectMapper mapper) {
    this.properties=properties; this.mapper=mapper;
  }

  public void externalId(String value) { identifier(value,512,2048,"external_id"); }
  public void textId(String value) { identifier(value,200,800,"text_id"); }

  private void identifier(String value,int maxPoints,int maxBytes,String name) {
    if(value==null || value.isEmpty() || !validUnicode(value) || value.codePointCount(0,value.length())>maxPoints
        || value.getBytes(StandardCharsets.UTF_8).length>maxBytes) {
      throw V2Exception.badRequest(name+" has invalid characters or exceeds its length limit");
    }
  }

  public void write(DocumentWriteRequest request,boolean replace) {
    if(request==null || (!request.hasMetadata() && !request.hasTexts()))
      throw V2Exception.badRequest("Provide metadata or texts");
    if(replace && (!request.hasMetadata() || !request.hasTexts()))
      throw V2Exception.badRequest("PUT requires metadata and texts");
    if(request.hasMetadata()) {
      JsonNode metadata=request.metadata();
      if(metadata==null || !metadata.isObject()) throw V2Exception.badRequest("metadata must be an object");
      validateMetadataStrings(metadata);
      if(jsonBytes(metadata)>properties.getMaxMetadataBytes())
        throw new V2Exception(413,"metadata_too_large","Metadata byte limit exceeded");
    }
    if(request.hasTexts()) {
      if(request.texts()==null) throw V2Exception.badRequest("texts must be an array");
      if(request.texts().size()>properties.getMaxTexts())
        throw new V2Exception(422,"too_many_texts","Document text limit exceeded");
      var keys=new HashSet<String>();
      for(var text:request.texts()) {
        if(text==null) throw V2Exception.badRequest("Every text must be an object");
        textId(text.textId());
        if(!keys.add(text.textId())) throw new V2Exception(422,"duplicate_text_id","Text keys must be unique within a document");
        if(text.kind()==null || !validUnicode(text.kind()) || points(text.kind())>60)
          throw V2Exception.badRequest("kind must be a string of at most 60 code points without NUL");
        if(text.language()!=null && (!validUnicode(text.language()) || points(text.language())>35))
          throw V2Exception.badRequest("language must be a string of at most 35 code points without NUL");
        if(text.content()==null || !validUnicode(text.content()))
          throw V2Exception.badRequest("content must be a Unicode string without NUL");
        if(text.content().isEmpty() || text.content().codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
          throw new V2Exception(422,"blank_text","Text content must not be blank");
        if(points(text.content())>properties.getMaxTextCodePoints())
          throw new V2Exception(422,"text_too_large","Text code-point limit exceeded");
      }
    }
  }

  public void snapshot(JsonNode metadata,Collection<SourceTextInput> texts) {
    if(texts.size()>properties.getMaxTexts()) throw new V2Exception(422,"too_many_texts","Document text limit exceeded");
    // A stable compact representation prevents successive PATCH requests bypassing the document limit.
    var body=mapper.createObjectNode();
    body.set("metadata",metadata); body.set("texts",mapper.valueToTree(texts));
    if(jsonBytes(body)>properties.getMaxDocumentBytes())
      throw new V2Exception(413,"document_too_large","Combined document byte limit exceeded");
  }

  private long jsonBytes(JsonNode value) {
    try { return mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8).length; }
    catch(java.io.IOException e) { throw V2Exception.badRequest("Invalid JSON value"); }
  }
  private void validateMetadataStrings(JsonNode root) {
    var pending=new ArrayDeque<JsonNode>(); pending.add(root);
    while(!pending.isEmpty()) {
      JsonNode node=pending.removeLast();
      if(node.isTextual() && !validUnicode(node.textValue()))
        throw V2Exception.badRequest("metadata strings must be valid Unicode without NUL");
      if(node.isObject()) node.fields().forEachRemaining(entry->{
        if(!validUnicode(entry.getKey())) throw V2Exception.badRequest("metadata keys must be valid Unicode without NUL");
        pending.add(entry.getValue());
      });
      else if(node.isArray()) node.forEach(pending::add);
    }
  }
  private int points(String value) { return value.codePointCount(0,value.length()); }
  private boolean validUnicode(String value) {
    for(int i=0;i<value.length();i++) {
      char c=value.charAt(i);
      if(c==0 || Character.isLowSurrogate(c)) return false;
      if(Character.isHighSurrogate(c) && (++i==value.length() || !Character.isLowSurrogate(value.charAt(i)))) return false;
    }
    return true;
  }
}
