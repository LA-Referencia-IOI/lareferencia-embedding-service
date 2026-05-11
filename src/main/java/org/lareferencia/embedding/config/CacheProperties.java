package org.lareferencia.embedding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

  private BackendType type = BackendType.POSTGRES;
  private final Rocksdb rocksdb = new Rocksdb();
  private final Sqlite sqlite = new Sqlite();

  public BackendType getType() {
    return type;
  }

  public void setType(BackendType type) {
    this.type = type;
  }

  public Rocksdb getRocksdb() {
    return rocksdb;
  }

  public Sqlite getSqlite() {
    return sqlite;
  }

  public enum BackendType {
    POSTGRES,
    ROCKSDB,
    SQLITE
  }

  public static class Rocksdb {

    private String path = "./data/rocksdb/embedding-cache";

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }
  }

  public static class Sqlite {

    private String path = "./data/sqlite/embedding-cache.db";

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }
  }
}