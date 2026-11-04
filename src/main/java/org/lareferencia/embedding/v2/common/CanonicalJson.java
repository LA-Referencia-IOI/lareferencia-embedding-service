package org.lareferencia.embedding.v2.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
public class CanonicalJson {
  private final ObjectMapper mapper;
  public CanonicalJson(ObjectMapper mapper) { this.mapper=mapper; }
  public String hash(Object value) {
    try {
      // The library accepts object/array roots; unwrap one canonical array element
      // to support every JSON root without changing object fingerprint bytes.
      String array=new JsonCanonicalizer("["+mapper.writeValueAsString(value)+"]").getEncodedString();
      byte[] canonical=array.substring(1,array.length()-1).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (java.io.IOException e) {
      throw V2Exception.badRequest("Payload cannot be canonicalized");
    } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  public String requestHash(MultiValueMap<String,String> query, Object body) {
    var normalized=new TreeMap<String,List<String>>();
    query.forEach((key,values) -> normalized.put(key,values.stream().sorted().toList()));
    return hash(Map.of("query",normalized,"body",body));
  }
  public String fileHash(Object options, String fileSha256) {
    if (fileSha256==null || !fileSha256.matches("[0-9a-f]{64}")) throw V2Exception.badRequest("Invalid file hash");
    return hash(Map.of("options",options,"file_sha256",fileSha256));
  }
}
