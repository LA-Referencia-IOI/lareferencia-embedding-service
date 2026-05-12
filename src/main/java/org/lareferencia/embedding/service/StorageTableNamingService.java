package org.lareferencia.embedding.service;

import java.util.UUID;

import org.lareferencia.embedding.exception.InvalidRequestException;
import org.springframework.stereotype.Service;

@Service
public class StorageTableNamingService {

  private static final String TABLE_PREFIX = "embeddings_";

  public String tableName(UUID storageId) {
    if (storageId == null) {
      throw new InvalidRequestException("Storage id must be provided");
    }
    String suffix = storageId.toString().replace('-', '_');
    return TABLE_PREFIX + suffix;
  }

  public String createdAtIndexName(UUID storageId) {
    return "idx_" + tableName(storageId) + "_created_at";
  }
}