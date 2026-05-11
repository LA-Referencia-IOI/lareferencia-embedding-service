package org.lareferencia.embedding.service;

import org.lareferencia.embedding.exception.InvalidRequestException;
import org.springframework.stereotype.Service;

@Service
public class StoreTableNamingService {

  private static final String TABLE_PREFIX = "embeddings_";

  public String tableName(long storeId) {
    if (storeId <= 0) {
      throw new InvalidRequestException("Store id must be a positive number");
    }
    String suffix = Long.toString(storeId);
    if (!suffix.chars().allMatch(Character::isDigit)) {
      throw new InvalidRequestException("Invalid store id for dynamic table generation");
    }
    return TABLE_PREFIX + suffix;
  }

  public String createdAtIndexName(long storeId) {
    return "idx_" + tableName(storeId) + "_created_at";
  }
}