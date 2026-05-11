package org.lareferencia.embedding.config;

import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.ai.vectorstore.pgvector")
public class PgVectorStoreProperties {

  private boolean initializeSchema = true;

  private String schemaName = PgVectorStore.DEFAULT_SCHEMA_NAME;

  private boolean schemaValidation = false;

  private PgVectorStore.PgIndexType indexType = PgVectorStore.PgIndexType.HNSW;

  private PgVectorStore.PgDistanceType distanceType = PgVectorStore.PgDistanceType.COSINE_DISTANCE;

  private int maxDocumentBatchSize = PgVectorStore.MAX_DOCUMENT_BATCH_SIZE;

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

  public PgVectorStore.PgIndexType getIndexType() {
    return indexType;
  }

  public void setIndexType(PgVectorStore.PgIndexType indexType) {
    this.indexType = indexType;
  }

  public PgVectorStore.PgDistanceType getDistanceType() {
    return distanceType;
  }

  public void setDistanceType(PgVectorStore.PgDistanceType distanceType) {
    this.distanceType = distanceType;
  }

  public int getMaxDocumentBatchSize() {
    return maxDocumentBatchSize;
  }

  public void setMaxDocumentBatchSize(int maxDocumentBatchSize) {
    this.maxDocumentBatchSize = maxDocumentBatchSize;
  }
}