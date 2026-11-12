package org.lareferencia.embedding.v2.importing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JsonlRecordReaderTest {
  private JsonlRecordReader reader(byte[] bytes,long offset,long lines,long limit) throws Exception {
    return new JsonlRecordReader(new ByteArrayInputStream(bytes),offset,lines,limit,new ObjectMapper());
  }
  @Test void bomBlankLinesMalformedRecordsAndResumeKeepOriginalByteOffsets() throws Exception {
    byte[] input="\ufeff  \r\n{\"id\":\"🧬\"}\nnot-json\n{\"id\":2}".getBytes(StandardCharsets.UTF_8);
    long checkpoint;try(var r=reader(input,0,0,100)) {
      var first=r.next();assertEquals(2,first.line());assertEquals("🧬",first.value().path("id").asText());
      checkpoint=first.nextOffset();var invalid=r.next();assertEquals(3,invalid.line());assertEquals("invalid_json",invalid.errorCode());
      assertEquals(2,r.next().value().path("id").asInt());assertNull(r.next());
    }
    try(var resumed=reader(input,checkpoint,2,100)) {assertEquals(3,resumed.next().line());assertEquals(4,resumed.next().line());assertNull(resumed.next());}
  }
  @Test void oversizedAndInvalidUtf8LinesDoNotPreventFollowingRecords() throws Exception {
    byte[] input=new byte[]{'a','b','c','d','e','f','\n',(byte)0xff,'\n','{','}', '\n'};
    try(var r=reader(input,0,0,4)) {
      assertEquals("record_too_large",r.next().errorCode());assertEquals("invalid_utf8",r.next().errorCode());
      assertTrue(r.next().value().isObject());assertNull(r.next());
    }
  }
  @Test void rejectsDuplicateKeysTrailingJsonAndNonObjectsWithoutExposingContents() throws Exception {
    byte[] input="{\"private\":1,\"private\":2}\n{} {}\n[]\n".getBytes(StandardCharsets.UTF_8);
    try(var r=reader(input,0,0,100)) {
      var invalid=r.next();assertEquals("invalid_json",invalid.errorCode());assertFalse(invalid.toString().contains("private"));
      assertEquals("invalid_json",r.next().errorCode());assertEquals("invalid_record",r.next().errorCode());
    }
  }
  @Test void discardsAnOversizedStreamWithoutRetainingTheWholeLine() throws Exception {
    InputStream longLine=new InputStream(){long remaining=32_000_000;public int read(){return remaining-->0?'x':-1;}};
    InputStream combined=new SequenceInputStream(longLine,new ByteArrayInputStream("\n{}\n".getBytes(StandardCharsets.UTF_8)));
    try(var reader=new JsonlRecordReader(combined,0,0,128,new ObjectMapper())) {
      assertEquals("record_too_large",reader.next().errorCode());assertTrue(reader.next().value().isObject());assertNull(reader.next());
    }
  }
}
