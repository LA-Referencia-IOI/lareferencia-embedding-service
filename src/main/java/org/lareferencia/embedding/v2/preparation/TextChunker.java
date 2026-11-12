package org.lareferencia.embedding.v2.preparation;

import java.util.ArrayList;
import java.util.List;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.springframework.stereotype.Component;

/** Computes offsets in Unicode code points against the unmodified source. */
@Component
public class TextChunker {
  public record Chunk(int ordinal,int start,int end,String text) {
    @Override public String toString() { return "Chunk[ordinal="+ordinal+", start="+start+", end="+end+", text=redacted]"; }
  }
  public List<Chunk> split(String content,String mode,int maxChars,int overlapChars) {
    List<Chunk> chunks=new ArrayList<>();
    forEach(content,mode,maxChars,overlapChars,chunks::add);
    return List.copyOf(chunks);
  }
  public void forEach(String content,String mode,int maxChars,int overlapChars,java.util.function.Consumer<Chunk> consumer) {
    if(content==null || content.isEmpty())
      throw new V2Exception(422,"blank_text","Source text must not be blank");
    int[] points=content.codePoints().toArray();
    if(blank(points,0,points.length)) throw new V2Exception(422,"blank_text","Source text must not be blank");
    if("none".equals(mode)) {consumer.accept(new Chunk(0,0,points.length,content));return;}
    if(!"characters-v1".equals(mode) || maxChars<1 || overlapChars<0 || overlapChars>=maxChars)
      throw new V2Exception(422,"invalid_chunking","Invalid chunking configuration");
    int start=0,ordinal=0;
    while(start<points.length) {
      int end=(int)Math.min((long)start+maxChars,points.length);
      if(end<points.length) {
        long minimum=(long)start+(maxChars+1L)/2;
        for(int position=end-2;position>=start;position--) {
          if(points[position]=='\n' && points[position+1]=='\n' && position+2L>=minimum) {end=position+2;break;}
        }
      }
      if(!blank(points,start,end)) consumer.accept(new Chunk(ordinal++,start,end,new String(points,start,end-start)));
      if(end==points.length) break;
      start=Math.max(start+1,end-overlapChars);
    }
  }
  private static boolean blank(int[] points,int start,int end) {
    for(int i=start;i<end;i++) if(!Character.isWhitespace(points[i]) && !Character.isSpaceChar(points[i])) return false;
    return true;
  }
}
