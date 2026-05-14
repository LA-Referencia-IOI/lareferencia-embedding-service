package org.lareferencia.embedding.service;

import java.util.UUID;

import org.lareferencia.embedding.exception.InvalidRequestException;
import org.springframework.stereotype.Service;

@Service
public class StoreTableNamingService {

  private static final String TABLE_PREFIX = "embeddings_";

  public String tableName(UUID storeId) {
    if (storeId == null) {
      throw new InvalidRequestException("Store id must be provided");
    }
    String suffix = storeId.toString().replace('-', '_');
    return TABLE_PREFIX + suffix;
  }

  public String createdAtIndexName(UUID storeId) {
    return "idx_" + tableName(storeId) + "_created_at";
  }
}