package org.lareferencia.embedding.v2.preparation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v2.common.V2Exception;

class TextChunkerTest {
  private final TextChunker chunker=new TextChunker();
  @Test void nonePreservesContentAndCountsUnicodeCodePoints() {
    var chunks=chunker.split("🧬e\u0301", "none",0,0);
    assertEquals(1,chunks.size());assertEquals(3,chunks.getFirst().end());assertEquals("🧬e\u0301",chunks.getFirst().text());
  }
  @Test void prefersLastParagraphBoundaryAndPreservesOverlap() {
    var chunks=chunker.split("abc\n\ndefghijkl", "characters-v1",8,2);
    assertEquals(List.of(0,3,9),chunks.stream().map(TextChunker.Chunk::start).toList());
    assertEquals(List.of("abc\n\n","\n\ndefghi","hijkl"),chunks.stream().map(TextChunker.Chunk::text).toList());
    assertEquals(List.of(0,1,2),chunks.stream().map(TextChunker.Chunk::ordinal).toList());
  }
  @Test void crlfIsNotAParagraphSeparatorAndEmojiOffsetsAreStable() {
    var chunks=chunker.split("🧬a\r\n\r\nbcd", "characters-v1",6,0);
    assertEquals(6,chunks.getFirst().end());assertEquals("🧬a\r\n\r\n",chunks.getFirst().text());
    assertEquals("bcd",chunks.getLast().text());
  }
  @Test void largeOverlapProgressesAndBlankWindowsAreSkipped() {
    var chunks=chunker.split("a     z", "characters-v1",3,2);
    assertEquals(List.of("a  ","  z"),chunks.stream().map(TextChunker.Chunk::text).toList());
    assertEquals(List.of(0,1),chunks.stream().map(TextChunker.Chunk::ordinal).toList());
  }
  @Test void rejectsBlankAndInvalidBounds() {
    assertThrows(V2Exception.class,()->chunker.split("\u00a0 ","none",0,0));
    assertThrows(V2Exception.class,()->chunker.split("hello","characters-v1",3,3));
  }
}
