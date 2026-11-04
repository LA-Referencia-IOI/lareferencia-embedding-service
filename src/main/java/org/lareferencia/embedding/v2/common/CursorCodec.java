package org.lareferencia.embedding.v2.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Component;

/** Opaque page positions, bound to route and selection. Cursors confer no access rights. */
@Component
public class CursorCodec {
  private final ObjectMapper mapper;
  public CursorCodec(ObjectMapper mapper) {
    this.mapper=mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  }
  public record Position(int version,String scope,String selection,List<String> keys) {}
  public String encode(String scope,String selection,List<String> keys) {
    try { return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(new Position(1,scope,selection,keys))); }
    catch(java.io.IOException e) { throw new IllegalStateException(e); }
  }
  public List<String> decode(String cursor,String scope,String selection) {
    if (cursor==null) return List.of();
    if (cursor.isEmpty() || cursor.length()>4096) throw V2Exception.badRequest("Invalid cursor");
    try {
      var p=mapper.readValue(Base64.getUrlDecoder().decode(cursor),Position.class);
      if (p.version()!=1 || !scope.equals(p.scope()) || !selection.equals(p.selection()) || p.keys()==null
          || p.keys().isEmpty() || p.keys().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException();
      return p.keys();
    } catch (java.io.IOException | IllegalArgumentException e) { throw V2Exception.badRequest("Invalid or out-of-scope cursor"); }
  }
}
