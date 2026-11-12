package org.lareferencia.embedding.v2.importing;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stores only generated opaque names under the configured import root. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class DurableImportStore {
  public static final class StoredFile implements AutoCloseable {
    private final String key,sha256;private final long byteSize;private final FileChannel channel;private final FileLock lock;
    private StoredFile(String key,String sha256,long byteSize,FileChannel channel,FileLock lock) {
      this.key=key;this.sha256=sha256;this.byteSize=byteSize;this.channel=channel;this.lock=lock;
    }
    public String key(){return key;} public String sha256(){return sha256;} public long byteSize(){return byteSize;}
    @Override public void close(){try{lock.close();}catch(IOException ignored){}try{channel.close();}catch(IOException ignored){}}
  }
  private final Path root;private final long maxBytes;private final Semaphore uploads;
  private record FileStamp(long size,java.nio.file.attribute.FileTime modified,Object fileKey,String sha256) { }
  private final Map<String,FileStamp> verified=Collections.synchronizedMap(new LinkedHashMap<>(128,0.75f,true) {
    @Override protected boolean removeEldestEntry(Map.Entry<String,FileStamp> entry){return size()>1024;}
  });
  private final Object[] verificationLocks=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
  public DurableImportStore(DvpProperties properties) {
    this.root=Path.of(properties.getFileRoot()).toAbsolutePath().normalize();
    this.maxBytes=properties.getMaxFileBytes();this.uploads=new Semaphore(properties.getMaxConcurrentUploads());
  }
  public StoredFile store(InputStream input) {
    if(!uploads.tryAcquire()) throw new V2Exception(429,"upload_busy","Upload capacity is exhausted");
    Path temp=null;boolean published=false,handedOff=false;Path destination=null;FileChannel channel=null;FileLock lock=null;
    try {
      Files.createDirectories(root);
      if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid storage root");
      String id=UUID.randomUUID().toString();temp=root.resolve(id+".part");destination=root.resolve(id+".data");
      MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
      channel=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);lock=channel.lock();
      byte[] buffer=new byte[64*1024];int count;
      while((count=input.read(buffer))!=-1) {
        if(count==0) continue;
        if(count>maxBytes-total) throw new V2Exception(413,"file_too_large","Import file byte limit exceeded");
        total+=count;digest.update(buffer,0,count);
        ByteBuffer bytes=ByteBuffer.wrap(buffer,0,count);while(bytes.hasRemaining())channel.write(bytes);
      }
      channel.force(true);
      Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE);published=true;
      // Persist the directory entry before any job can refer to the file.
      try(FileChannel directory=FileChannel.open(root,StandardOpenOption.READ)) {directory.force(true);}
      var stored=new StoredFile(destination.getFileName().toString(),HexFormat.of().formatHex(digest.digest()),total,channel,lock);
      handedOff=true;return stored;
    } catch(V2Exception e) {throw e;}
    catch(IOException | java.security.NoSuchAlgorithmException e) {throw new V2Exception(503,"import_storage_unavailable","Import storage is unavailable");}
    finally {
      if(!handedOff) {
        if(lock!=null)try{lock.close();}catch(IOException ignored){}
        if(channel!=null)try{channel.close();}catch(IOException ignored){}
        Path abandoned=published?destination:temp;if(abandoned!=null)try{Files.deleteIfExists(abandoned);}catch(IOException ignored){}
      }
      uploads.release();
    }
  }
  public InputStream open(String key) {
    Path path=resolve(key);
    try {return Channels.newInputStream(FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));}
    catch(NoSuchFileException e) {throw new V2Exception(409,"import_file_unavailable","Import file is unavailable");}
    catch(IOException e) {throw new V2Exception(503,"import_storage_unavailable","Import storage is unavailable");}
  }
  /** Verify immutable file bytes before processing; cached stamps avoid rereading a large file per batch. */
  public InputStream openVerified(String key,String expectedHash,long expectedSize) {
    Path path=resolve(key);
    if(expectedHash==null || !expectedHash.matches("[0-9a-f]{64}") || expectedSize<0)
      throw new V2Exception(409,"import_file_corrupt","Import file metadata is invalid");
    synchronized(verificationLocks[(key.hashCode() & 0x7fffffff)%verificationLocks.length]) {
      try {
        var before=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile() || before.size()!=expectedSize)throw new V2Exception(409,"import_file_corrupt","Import file integrity check failed");
        var stamp=new FileStamp(before.size(),before.lastModifiedTime(),before.fileKey(),expectedHash);
        if(!stamp.equals(verified.get(key))) {
          MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
          try(InputStream input=open(key)) {byte[] buffer=new byte[64*1024];int n;while((n=input.read(buffer))!=-1){if(n==0)continue;total+=n;digest.update(buffer,0,n);}}
          var after=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
          if(total!=expectedSize || after.size()!=before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())
              || !Objects.equals(after.fileKey(),before.fileKey()) || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash))
            throw new V2Exception(409,"import_file_corrupt","Import file integrity check failed");
          verified.put(key,stamp);
        }
        return open(key);
      } catch(NoSuchFileException e) {throw new V2Exception(409,"import_file_unavailable","Import file is unavailable");}
      catch(IOException | java.security.NoSuchAlgorithmException e) {throw new V2Exception(503,"import_storage_unavailable","Import storage is unavailable");}
    }
  }
  /** Remove only a file created for a request that is known to have replayed another job. */
  public boolean discardRedundant(StoredFile file) {
    try {return Files.deleteIfExists(resolve(file.key()));}
    catch(IOException e) {return false;}
  }
  /** Enumerate a bounded lexical page of service-owned stale filenames. */
  List<String> staleKeys(java.time.Instant cutoff,String after,int limit) {
    if(limit<1 || limit>1000)throw new IllegalArgumentException("Invalid page size");
    if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return List.of();
    var selected=new PriorityQueue<String>(Comparator.reverseOrder());
    try(var paths=Files.list(root)) {
      paths.forEach(path->{
        String key=path.getFileName().toString();
        if(!generatedKey(key) || (after!=null && key.compareTo(after)<=0) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return;
        try {if(Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(cutoff))return;}
        catch(IOException e){return;}
        if(selected.size()<limit)selected.add(key);else if(key.compareTo(selected.peek())<0){selected.poll();selected.add(key);}
      });
    } catch(IOException e) {throw new V2Exception(503,"import_storage_unavailable","Import storage is unavailable");}
    var result=new ArrayList<>(selected);Collections.sort(result);return List.copyOf(result);
  }
  /** Hold an OS file lock while the caller checks registry ownership and performs deletion. */
  boolean withStaleLock(String key,java.time.Instant cutoff,java.util.function.Predicate<Runnable> action) {
    if(!generatedKey(key))throw new IllegalArgumentException("Invalid internal import key");Path path=root.resolve(key);
    try(FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
      try(FileLock lock=channel.tryLock()) {
        if(lock==null || Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(cutoff))return false;
        return action.test(()->{try{Files.deleteIfExists(path);}catch(IOException e){throw new UncheckedIOException(e);}});
      }
    } catch(java.nio.channels.OverlappingFileLockException | NoSuchFileException e) {return false;}
    catch(IOException | UncheckedIOException e) {throw new V2Exception(503,"import_storage_unavailable","Import storage is unavailable");}
  }
  private boolean generatedKey(String key) {
    return key!=null && key.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(data|part)");
  }
  private Path resolve(String key) {
    if(key==null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.data"))
      throw new IllegalArgumentException("Invalid internal import key");
    return root.resolve(key);
  }
}
