package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Bounded byte-level framing for a JSON container; values are parsed only after size checks. */
public final class JsonDocumentReader implements AutoCloseable {
  public record Record(long ordinal,JsonNode value,String errorCode) {
    @Override public String toString() {return "Record[ordinal="+ordinal+", value=redacted, errorCode="+errorCode+"]";}
  }
  public static final class SyntaxException extends IOException {public SyntaxException(){super("Invalid JSON import container");}}
  private record Framed(byte[] bytes,boolean oversized) { }
  private final PushbackInputStream input;private final ObjectMapper mapper;private final int maxRecordBytes;
  private final Set<String> fields=new HashSet<>();
  private long ordinal;private boolean first=true,finished=false,schemaSeen=false,documentsSeen=false;
  public JsonDocumentReader(InputStream input,long maxRecordBytes,ObjectMapper base) throws IOException {
    if(maxRecordBytes<1 || maxRecordBytes>Integer.MAX_VALUE-8L)throw new IllegalArgumentException("Invalid record limit");
    this.input=new PushbackInputStream(new BufferedInputStream(Objects.requireNonNull(input),64*1024),3);
    this.mapper=base.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    this.maxRecordBytes=(int)maxRecordBytes;consumeBom();expect('{');locateDocuments();
  }
  /** False means callers must preflight the complete container before applying any records. */
  public boolean schemaKnownBeforeDocuments() {return schemaSeen;}
  public Record next() throws IOException {
    if(finished)return null;
    int separator=nonWhitespace();
    if(first) {first=false;if(separator==']'){finishObject();return null;}unread(separator);}
    else {
      if(separator==']'){finishObject();return null;}
      if(separator!=',')throw new SyntaxException();
      int next=nonWhitespace();if(next==']' || next<0)throw new SyntaxException();unread(next);
    }
    Framed framed=value(maxRecordBytes);ordinal++;
    if(framed.oversized())return new Record(ordinal,null,"record_too_large");
    JsonNode node=parse(framed.bytes());
    if(!node.isObject())return new Record(ordinal,null,"invalid_record");
    return new Record(ordinal,node,null);
  }
  private void locateDocuments() throws IOException {
    boolean firstField=true;
    for(;;) {
      int separator=nonWhitespace();
      if(!firstField) {if(separator!=',')throw new SyntaxException();separator=nonWhitespace();}
      if(separator!='"')throw new SyntaxException();unread(separator);String key=fieldName();expect(':');
      if(!fields.add(key))throw new SyntaxException();
      if(key.equals("schema_version")) {readVersion();}
      else if(key.equals("documents")) {expect('[');documentsSeen=true;return;}
      else throw new SyntaxException();
      firstField=false;
    }
  }
  private void finishObject() throws IOException {
    int separator=nonWhitespace();
    while(separator==',') {
      int begin=nonWhitespace();if(begin!='"')throw new SyntaxException();unread(begin);String key=fieldName();expect(':');
      if(!fields.add(key) || !key.equals("schema_version"))throw new SyntaxException();readVersion();separator=nonWhitespace();
    }
    if(separator!='}' || !schemaSeen || !documentsSeen || nonWhitespace()!=-1)throw new SyntaxException();
    finished=true;
  }
  private String fieldName() throws IOException {
    Framed value=value(2048);if(value.oversized())throw new SyntaxException();JsonNode node=parse(value.bytes());
    if(!node.isTextual())throw new SyntaxException();return node.textValue();
  }
  private void readVersion() throws IOException {
    Framed framed=value(100);if(framed.oversized())throw new SyntaxException();JsonNode node=parse(framed.bytes());
    if(!node.isTextual() || !"1".equals(node.textValue()))throw new SyntaxException();schemaSeen=true;
  }
  private JsonNode parse(byte[] data) throws IOException {
    try {
      String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data)).toString();
      JsonNode result=mapper.readTree(decoded);if(result==null)throw new SyntaxException();return result;
    } catch(CharacterCodingException | com.fasterxml.jackson.core.JsonProcessingException e) {throw new SyntaxException();}
  }
  private Framed value(int limit) throws IOException {
    int current=nonWhitespace();if(current<0)throw new SyntaxException();var out=new ByteArrayOutputStream(Math.min(limit,8192));boolean oversized=false;
    var syntax=new JsonSyntaxValidator();syntax.accept(current);
    int[] stack=new int[1000];int depth=0;boolean inString=false,escaped=false,container=current=='{' || current=='[';
    if(container)stack[depth++]=current;else if(current=='"')inString=true;
    if(out.size()<limit)out.write(current);else oversized=true;
    if(container || inString) {
      while(depth>0 || inString) {
        current=input.read();if(current<0)throw new SyntaxException();syntax.accept(current);
        if(out.size()<limit)out.write(current);else oversized=true;
        if(inString) {if(escaped)escaped=false;else if(current=='\\')escaped=true;else if(current=='"')inString=false;continue;}
        if(current=='"'){inString=true;continue;}
        if(current=='{' || current=='['){if(depth==stack.length)throw new SyntaxException();stack[depth++]=current;}
        else if(current=='}' || current==']') {
          if(depth==0 || (current=='}' && stack[depth-1]!='{') || (current==']' && stack[depth-1]!='['))throw new SyntaxException();depth--;
        }
      }
    } else {
      while((current=input.read())!=-1) {
        if(whitespace(current) || current==',' || current==']' || current=='}'){unread(current);break;}
        syntax.accept(current);
        if(out.size()<limit)out.write(current);else oversized=true;
      }
    }
    syntax.finish();return new Framed(out.toByteArray(),oversized);
  }
  private void consumeBom() throws IOException {
    byte[] bytes=input.readNBytes(3);if(bytes.length==3 && bytes[0]==(byte)0xef && bytes[1]==(byte)0xbb && bytes[2]==(byte)0xbf)return;
    input.unread(bytes);
  }
  private int nonWhitespace() throws IOException {int c;do{c=input.read();}while(whitespace(c));return c;}
  private boolean whitespace(int c) {return c==' ' || c=='\t' || c=='\r' || c=='\n';}
  private void expect(int wanted) throws IOException {if(nonWhitespace()!=wanted)throw new SyntaxException();}
  private void unread(int value) throws IOException {if(value>=0)input.unread(value);}
  public void close() throws IOException {input.close();}
}
