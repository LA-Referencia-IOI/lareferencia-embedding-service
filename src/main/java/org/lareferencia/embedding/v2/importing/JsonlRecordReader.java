package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Objects;

/** Streaming JSONL reader. Offsets always refer to original file bytes, including a leading BOM. */
public final class JsonlRecordReader implements AutoCloseable {
  public record Record(long line,long offset,long nextOffset,JsonNode value,String errorCode) {
    @Override public String toString() {return "Record[line="+line+", offset="+offset+", nextOffset="+nextOffset+", value=redacted, errorCode="+errorCode+"]";}
  }
  private final InputStream input;private final int maxRecordBytes;private final ObjectMapper mapper;
  private long offset,line;
  public JsonlRecordReader(InputStream input,long startOffset,long completedLines,long maxRecordBytes,ObjectMapper base) throws IOException {
    if(startOffset<0 || completedLines<0 || maxRecordBytes<1 || maxRecordBytes>Integer.MAX_VALUE-8L)
      throw new IllegalArgumentException("Invalid JSONL position or record limit");
    this.input=new BufferedInputStream(Objects.requireNonNull(input),64*1024);this.input.skipNBytes(startOffset);
    this.offset=startOffset;this.line=completedLines;this.maxRecordBytes=(int)maxRecordBytes;
    this.mapper=base.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }
  /** Returns null at EOF; malformed and oversized lines are returned as error records. */
  public Record next() throws IOException {
    for(;;) {
      long start=offset;boolean oversized=false;int next;var bytes=new ByteArrayOutputStream(Math.min(maxRecordBytes,8192));
      while((next=input.read())!=-1) {
        offset++;
        if(next=='\n') break;
        if(bytes.size()<maxRecordBytes) bytes.write(next);else oversized=true;
      }
      if(next==-1 && offset==start) return null;
      line++;
      if(oversized) return new Record(line,start,offset,null,"record_too_large");
      byte[] data=bytes.toByteArray();int skip=start==0 && data.length>=3 && data[0]==(byte)0xef && data[1]==(byte)0xbb && data[2]==(byte)0xbf?3:0;
      String text;
      try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data,skip,data.length-skip)).toString();}
      catch(CharacterCodingException e) {return new Record(line,start,offset,null,"invalid_utf8");}
      if(text.chars().allMatch(c->c==' ' || c=='\t' || c=='\r')) continue;
      try {
        JsonNode value=mapper.readTree(text);
        if(value==null || !value.isObject()) return new Record(line,start,offset,null,"invalid_record");
        return new Record(line,start,offset,value,null);
      } catch(IOException | RuntimeException e) {return new Record(line,start,offset,null,"invalid_json");}
    }
  }
  public void close() throws IOException {input.close();}
  public long offset() {return offset;}
  public long completedLines() {return line;}
}
