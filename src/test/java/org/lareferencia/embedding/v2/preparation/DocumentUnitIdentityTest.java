package org.lareferencia.embedding.v2.preparation;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DocumentUnitIdentityTest {
  @Test void assignsStableDistinctKeysWithoutSourceIdentifiers() {
    var first=new DocumentUnitIdentity();
    String one=first.next("title"),two=first.next("abstract"),three=first.next("title");
    assertNotEquals(one,two);
    assertNotEquals(one,three);
    var retry=new DocumentUnitIdentity();
    assertEquals(one,retry.next("title"));
    assertEquals(two,retry.next("abstract"));
    assertEquals(three,retry.next("title"));
  }
}
