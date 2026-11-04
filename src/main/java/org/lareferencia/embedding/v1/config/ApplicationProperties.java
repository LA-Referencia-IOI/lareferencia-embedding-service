package org.lareferencia.embedding.v1.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.embedding")
public class ApplicationProperties {

  private int maxBatchSize = 128;
  private int maxInputLength = 8192;

  public int getMaxBatchSize() {
    return maxBatchSize;
  }

  public void setMaxBatchSize(int maxBatchSize) {
    this.maxBatchSize = maxBatchSize;
  }

  public int getMaxInputLength() {
    return maxInputLength;
  }

  public void setMaxInputLength(int maxInputLength) {
    this.maxInputLength = maxInputLength;
  }
}