package org.lareferencia.embedding.v2.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties("app.dvp")
public class DvpProperties {
  public enum Role { API, WORKER, ALL }
  private boolean enabled = false;
  private boolean workersEnabled = false;
  @NotNull private Role role = Role.API;
  @Min(1) private int readConnections = 8;
  @Min(1) private int coordinationConnections = 2;
  @Min(1) private int lockConnections = 4;
  @Min(250) private long connectionTimeoutMs = 1000;
  @Min(1) private int inferenceConcurrency = 4;
  @Min(1) private long maxRequestBytes = 16L * 1024 * 1024;
  @Min(1) private long maxDocumentBytes = 16L * 1024 * 1024;
  @Min(1) private int maxTexts = 1000;
  @Min(1) private int maxTextCodePoints = 2_000_000;
  @Min(1) private int maxMetadataBytes = 256 * 1024;
  @Min(1) private int maxQueryCodePoints = 8192;
  @Min(1) private int exactTimeoutSeconds = 60;
  @Min(1) private int approximateTimeoutSeconds = 10;
  @Min(1) private int writeTimeoutSeconds = 30;
  @Min(1) private int coordinationTimeoutSeconds = 5;
  @Min(1) private int exactConcurrency = 2;
  @Min(1) private int approximateConcurrency = 16;
  @Min(1) private long maxFileBytes = 10L * 1024 * 1024 * 1024;
  @NotNull private String fileRoot = "./data/imports";
  @Min(30) private int fileRetentionDays = 30;
  @Min(24) private int temporaryRetentionHours = 24;
  @Min(7) private int idempotencyRetentionDays = 7;
  @Min(1) private int leaseSeconds = 120;
  @Min(1) private int heartbeatSeconds = 20;
  @Min(1) private int maxActiveItems = 1000;
  @Min(1) private int documentBatchSize = 100;
  @Min(1) private int providerBatchSize = 128;
  @NotNull private Duration providerConnectTimeout = Duration.ofSeconds(5);
  @NotNull private Duration providerRequestTimeout = Duration.ofSeconds(30);
}
