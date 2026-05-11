package org.lareferencia.embedding.cache;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RocksDbEmbeddingCache implements EmbeddingCache, Closeable {

  static {
    RocksDB.loadLibrary();
  }

  private final Options options;
  private final RocksDB rocksDb;

  public RocksDbEmbeddingCache(String databasePath) throws RocksDBException {
    try {
      Path storagePath = Path.of(databasePath).toAbsolutePath();
      Path parent = storagePath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }

      Options configuredOptions = new Options()
          .setCreateIfMissing(true)
          .setCompressionType(CompressionType.LZ4_COMPRESSION);
      try {
        this.rocksDb = RocksDB.open(configuredOptions, storagePath.toString());
        this.options = configuredOptions;
      } catch (RocksDBException | RuntimeException exception) {
        configuredOptions.close();
        throw exception;
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to initialize RocksDB storage", exception);
    }
  }

  @Override
  public Optional<List<Double>> get(String key) {
    try {
      log.debug("Checking RocksDB cache for key: {}", key);
      byte[] payload = rocksDb.get(key.getBytes(StandardCharsets.UTF_8));
      if (payload == null) {
        return Optional.empty();
      }
      return Optional.of(deserialize(payload));
    } catch (RocksDBException exception) {
      throw new IllegalStateException("Failed to read embedding from RocksDB", exception);
    }
  }

  @Override
  public void put(String key, List<Double> embedding) {
    try {
      log.debug("Storing embedding in RocksDB cache for key: {}", key);
      rocksDb.put(key.getBytes(StandardCharsets.UTF_8), serialize(embedding));
    } catch (RocksDBException exception) {
      throw new IllegalStateException("Failed to store embedding in RocksDB", exception);
    }
  }

  @Override
  public void close() throws IOException {
    rocksDb.close();
    options.close();
  }

  private static byte[] serialize(List<Double> embedding) {
    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + (embedding.size() * Double.BYTES))
        .order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(embedding.size());
    for (Double value : embedding) {
      buffer.putDouble(value);
    }
    return buffer.array();
  }

  private static List<Double> deserialize(byte[] payload) {
    ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    int size = buffer.getInt();
    List<Double> embedding = new ArrayList<>(size);
    for (int index = 0; index < size; index++) {
      embedding.add(buffer.getDouble());
    }
    return embedding;
  }
}