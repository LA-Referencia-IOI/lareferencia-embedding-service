package org.lareferencia.embedding.v2.importing;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;

class DurableImportStoreTest {
  @TempDir Path root;
  private DurableImportStore store(long limit) {var p=new DvpProperties();p.setFileRoot(root.toString());p.setMaxFileBytes(limit);return new DurableImportStore(p);}
  @Test void publishesCompleteBytesUnderOpaqueNameAndHashesOriginalInput() throws Exception {
    var storage=store(100);byte[] body="\ufeffhello\n".getBytes(StandardCharsets.UTF_8);
    try(var file=storage.store(new ByteArrayInputStream(body))) {
    assertEquals(body.length,file.byteSize());assertArrayEquals(body,storage.open(file.key()).readAllBytes());
    assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body)),file.sha256());
    assertTrue(file.key().endsWith(".data"));assertFalse(file.key().contains("hello"));
    }
  }
  @Test void oversizeOrBrokenUploadDoesNotPublishPartialFile() throws Exception {
    var storage=store(3);assertEquals(413,assertThrows(V2Exception.class,()->storage.store(new ByteArrayInputStream(new byte[4]))).status());
    try(var files=Files.list(root)){assertEquals(0,files.count());}
    InputStream broken=new InputStream(){public int read() throws IOException {throw new IOException("private detail");}};
    var failure=assertThrows(V2Exception.class,()->storage.store(broken));assertEquals(503,failure.status());assertFalse(failure.getMessage().contains("private detail"));
    try(var files=Files.list(root)){assertEquals(0,files.count());}
  }
  @Test void rejectsPathTraversalAndMissingFile() {
    var storage=store(10);assertThrows(IllegalArgumentException.class,()->storage.open("../secret"));
    assertEquals(409,assertThrows(V2Exception.class,()->storage.open(java.util.UUID.randomUUID()+".data")).status());
  }
  @Test void verifiesFileHashAndRejectsCorruptionEvenWhenLengthIsUnchanged() throws Exception {
    var storage=store(100);String key,hash;
    try(var file=storage.store(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)))) {key=file.key();hash=file.sha256();}
    try(var input=storage.openVerified(key,hash,3)){assertEquals("abc",new String(input.readAllBytes(),StandardCharsets.UTF_8));}
    Files.writeString(root.resolve(key),"xyz");
    Files.setLastModifiedTime(root.resolve(key),java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(2)));
    assertEquals("import_file_corrupt",assertThrows(V2Exception.class,()->storage.openVerified(key,hash,3)).code());
  }
}
