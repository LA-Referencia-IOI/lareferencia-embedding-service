package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.lareferencia.embedding.v2.common.CanonicalJson;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/** RFC 8785 request identity plus exact numeric values for arbitrary JSONB metadata. */
@Component
public class DocumentFingerprint {
  private final CanonicalJson canonical;
  private final ObjectMapper mapper;
  public DocumentFingerprint(CanonicalJson canonical,ObjectMapper mapper) { this.canonical=canonical; this.mapper=mapper; }
  public String hash(MultiValueMap<String,String> query,DocumentWriteRequest request,Long revision) {
    var payload=mapper.createObjectNode();
    if(request.hasMetadata()) payload.set("metadata",request.metadata());
    if(request.hasTexts()) payload.set("texts",mapper.valueToTree(request.texts()));
    return hash(query,payload,revision);
  }
  public String hash(MultiValueMap<String,String> query,JsonNode payload,Long revision) {
    var identity=mapper.createObjectNode();
    identity.set("body",payload);
    if(revision==null) identity.putNull("if_match"); else identity.put("if_match",revision.toString());
    var numbers=new TreeMap<String,String>();
    if(payload!=null) collectNumbers(payload,"",numbers);
    // The numeric supplement prevents IEEE-754 canonicalization from equating distinct JSONB numbers.
    return canonical.hash(Map.of("canonical_request",canonical.requestHash(query,identity),"exact_numbers",numbers));
  }
  private void collectNumbers(JsonNode node,String path,Map<String,String> numbers) {
    if(node.isNumber()) {
      numbers.put(path,node.decimalValue().stripTrailingZeros().toString());
    } else if(node.isObject()) {
      node.fields().forEachRemaining(entry -> collectNumbers(entry.getValue(),path+"/"+
          entry.getKey().replace("~","~0").replace("/","~1"),numbers));
    } else if(node.isArray()) {
      for(int i=0;i<node.size();i++) collectNumbers(node.get(i),path+"/"+i,numbers);
    }
  }
}
