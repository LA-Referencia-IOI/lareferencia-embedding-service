package org.lareferencia.embedding.v2.importing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JsonDocumentReaderTest {
  private JsonDocumentReader reader(String text,int limit) throws Exception {return new JsonDocumentReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),limit,new ObjectMapper());}
  @Test void handlesBomNestedValuesAndSchemaBeforeDocuments() throws Exception {
    try(var r=reader("\ufeff{\"schema_version\":\"1\",\"documents\":[{\"a\":\"}\\\"[\",\"b\":[1,{\"x\":2}]},{\"a\":\"🧬\"}]}",200)) {
      assertTrue(r.schemaKnownBeforeDocuments());assertEquals(1,r.next().ordinal());assertEquals("🧬",r.next().value().path("a").asText());assertNull(r.next());
    }
  }
  @Test void acceptsSchemaAfterDocumentsAndReportsThatPreflightIsRequired() throws Exception {
    try(var r=reader("{\"documents\":[{},{}],\"schema_version\":\"1\"}",20)) {
      assertFalse(r.schemaKnownBeforeDocuments());assertEquals(1,r.next().ordinal());assertEquals(2,r.next().ordinal());assertNull(r.next());
    }
  }
  @Test void oversizedRecordsAreBoundedAndLaterRecordsRemainAvailable() throws Exception {
    try(var r=reader("{\"schema_version\":\"1\",\"documents\":[{\"text\":\"abcdefghi\"},{}]}",8)) {
      assertEquals("record_too_large",r.next().errorCode());assertTrue(r.next().value().isObject());assertNull(r.next());
    }
  }
  @Test void malformedGlobalJsonFailsAfterPreviouslyReadRecords() throws Exception {
    try(var r=reader("{\"schema_version\":\"1\",\"documents\":[{}, {\"x\":}]}",100)) {
      assertTrue(r.next().value().isObject());assertThrows(JsonDocumentReader.SyntaxException.class,r::next);
    }
    try(var r=reader("{\"schema_version\":\"1\",\"documents\":[{},]}",100)) {
      assertTrue(r.next().value().isObject());assertThrows(JsonDocumentReader.SyntaxException.class,r::next);
    }
  }
  @Test void duplicateFieldsAndInvalidSchemaAreRejected() throws Exception {
    assertThrows(JsonDocumentReader.SyntaxException.class,()->reader("{\"schema_version\":\"2\",\"documents\":[]}",100));
    try(var r=reader("{\"schema_version\":\"1\",\"documents\":[{\"a\":1,\"a\":2}]}",100)) {assertThrows(JsonDocumentReader.SyntaxException.class,r::next);}
  }
  @Test void malformedOversizedRecordStillFailsTheGlobalJson() throws Exception {
    try(var r=reader("{\"schema_version\":\"1\",\"documents\":[{\"text\":\""+"a".repeat(1000)+"\",\"bad\":}]}",16)) {
      assertThrows(JsonDocumentReader.SyntaxException.class,r::next);
    }
  }
  @Test void rejectsInvalidUtf8EvenAfterRecordLimitIsExceeded() throws Exception {
    var prefix="{\"schema_version\":\"1\",\"documents\":[{\"text\":\"".getBytes(StandardCharsets.UTF_8);
    var suffix="\"}]}".getBytes(StandardCharsets.UTF_8);var bytes=new ByteArrayOutputStream();bytes.write(prefix);bytes.write("a".repeat(100).getBytes(StandardCharsets.UTF_8));bytes.write(0xff);bytes.write(suffix);
    try(var r=new JsonDocumentReader(new ByteArrayInputStream(bytes.toByteArray()),8,new ObjectMapper())) {assertThrows(JsonDocumentReader.SyntaxException.class,r::next);}
  }
  @Test void oversizedJsonIsStreamedAndFollowingRecordRemainsReadable() throws Exception {
    InputStream prefix=new ByteArrayInputStream("{\"schema_version\":\"1\",\"documents\":[{\"text\":\"".getBytes(StandardCharsets.UTF_8));
    InputStream longString=new InputStream(){long remaining=32_000_000;public int read(){return remaining-->0?'a':-1;}};
    InputStream suffix=new ByteArrayInputStream("\"},{}]}".getBytes(StandardCharsets.UTF_8));
    try(var r=new JsonDocumentReader(new SequenceInputStream(java.util.Collections.enumeration(java.util.List.of(prefix,longString,suffix))),128,new ObjectMapper())) {
      assertEquals("record_too_large",r.next().errorCode());assertTrue(r.next().value().isObject());assertNull(r.next());
    }
  }
  @Test void acceptsDiverseValidJsonRecordsAndRejectsMalformedNumbers() throws Exception {
    var mapper=new ObjectMapper();var random=new java.util.Random(1234);
    for(int n=0;n<200;n++) {
      var value=mapper.createObjectNode().put("n",random.nextLong()).put("f",random.nextDouble())
          .put("s","line\\\"\n🧬"+n).put("b",n%2==0);
      value.putArray("items").addNull().add(true).addObject().put("nested",n);
      try(var r=reader("{\"schema_version\":\"1\",\"documents\":["+mapper.writeValueAsString(value)+"]}",4096)) {
        var parsed=r.next().value();assertEquals(value.path("n").longValue(),parsed.path("n").longValue());
        assertEquals(value.path("s").textValue(),parsed.path("s").textValue());assertEquals(n,parsed.path("items").get(2).path("nested").asInt());assertNull(r.next());
      }
    }
    for(String value:java.util.List.of("01","1.","1e","-","[1,]","{\"x\":true,}")) {
      try(var r=reader("{\"schema_version\":\"1\",\"documents\":["+value+"]}",2)) {
        assertThrows(JsonDocumentReader.SyntaxException.class,r::next);
      }
    }
  }
}
