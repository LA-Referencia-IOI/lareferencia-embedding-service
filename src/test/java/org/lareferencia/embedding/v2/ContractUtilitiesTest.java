package org.lareferencia.embedding.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v2.common.*;
import org.lareferencia.embedding.v2.config.VectorCapabilityCheck;
import org.springframework.util.LinkedMultiValueMap;

class ContractUtilitiesTest {
  private final ObjectMapper mapper = new ObjectMapper();
  @Test void canonicalHashesIgnoreObjectOrderAndEquivalentNumbersButPreserveStrings() throws Exception {
    var canonical=new CanonicalJson(mapper);
    assertEquals(canonical.hash(mapper.readTree("{\"b\":1.0,\"a\":2}")),
        canonical.hash(mapper.readTree("{\"a\":2,\"b\":1}")));
    assertNotEquals(canonical.hash("A"),canonical.hash("a"));
    var query=new LinkedMultiValueMap<String,String>(); query.add("include","b");query.add("include","a");
    var other=new LinkedMultiValueMap<String,String>(); other.add("include","a");other.add("include","b");
    assertEquals(canonical.requestHash(query,Map.of()),canonical.requestHash(other,Map.of()));
    assertNotEquals(canonical.fileHash(Map.of("mode","merge"),"a".repeat(64)),
        canonical.fileHash(Map.of("mode","replace"),"a".repeat(64)));
  }
  @Test void cursorsAreBoundToRouteSelectionAndVersion() {
    var codec=new CursorCodec(mapper);
    var token=codec.encode("/collections","",List.of(UUID.randomUUID().toString()));
    assertEquals(1,codec.decode(token,"/collections","").size());
    assertThrows(V2Exception.class,()->codec.decode(token,"/profiles",""));
    assertThrows(V2Exception.class,()->codec.decode(token,"/collections","metadata"));
    assertThrows(V2Exception.class,()->codec.decode("!bad","/collections",""));
  }
  @Test void preconditionsAndExtensionCapabilitiesAreExplicit() {
    assertNull(Revisions.ifMatch(null)); assertEquals(3L,Revisions.ifMatch("\"3\""));
    assertThrows(V2Exception.class,()->Revisions.ifMatch("W/\"3\""));
    assertThrows(V2Exception.class,()->Revisions.ifMatch("\"0\""));
    assertThrows(V2Exception.class,()->Revisions.ifMatch("\"99999999999999999999999\""));
    assertThrows(V2Exception.class,()->Revisions.requireMatch(3L,null));
    assertTrue(VectorCapabilityCheck.compatible("0.8.0"));
    assertTrue(VectorCapabilityCheck.compatible("1.0.0"));
    assertFalse(VectorCapabilityCheck.compatible("0.7.4"));
    assertFalse(VectorCapabilityCheck.compatible(null));
  }
}
