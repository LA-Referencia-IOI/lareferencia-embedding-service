package org.lareferencia.embedding.v1.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.pgvector")
public class PgVectorStoreProperties {

  private boolean initializeSchema = true;

  private String schemaName = "public";

  private boolean schemaValidation = false;

  private IndexType indexType = IndexType.NONE;

  private DistanceType distanceType = DistanceType.COSINE_DISTANCE;

  private int maxDocumentBatchSize = 10000;

  public enum IndexType {
    NONE,
    HNSW,
    IVFFLAT
  }

  public enum DistanceType {
    COSINE_DISTANCE,
    EUCLIDEAN_DISTANCE,
    NEGATIVE_INNER_PRODUCT
  }

  public boolean isInitializeSchema() {
    return initializeSchema;
  }

  public void setInitializeSchema(boolean initializeSchema) {
    this.initializeSchema = initializeSchema;
  }

  public String getSchemaName() {
    return schemaName;
  }

  public void setSchemaName(String schemaName) {
    this.schemaName = schemaName;
  }

  public boolean isSchemaValidation() {
    return schemaValidation;
  }

  public void setSchemaValidation(boolean schemaValidation) {
    this.schemaValidation = schemaValidation;
  }

  public IndexType getIndexType() {
    return indexType;
  }

  public void setIndexType(IndexType indexType) {
    this.indexType = indexType;
  }

  public DistanceType getDistanceType() {
    return distanceType;
  }

  public void setDistanceType(DistanceType distanceType) {
    this.distanceType = distanceType;
  }

  public int getMaxDocumentBatchSize() {
    return maxDocumentBatchSize;
  }

  public void setMaxDocumentBatchSize(int maxDocumentBatchSize) {
    this.maxDocumentBatchSize = maxDocumentBatchSize;
  }
}