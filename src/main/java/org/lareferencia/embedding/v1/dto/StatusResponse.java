package org.lareferencia.embedding.v1.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StatusResponse(
    Instant timestamp,
    SystemStatus system,
    DatabaseStatus database) {

  public record SystemStatus(
      CpuStatus cpu,
      MemoryStatus ram,
      DiskStatus disk) {
  }

  public record CpuStatus(
      int availableProcessors,
      Double usagePercent,
      Double freePercent,
      double systemLoadAverage) {
  }

  public record MemoryStatus(
      long totalBytes,
      long usedBytes,
      long freeBytes,
      double usagePercent,
      double freePercent,
      String total,
      String used,
      String free) {
  }

  public record DiskStatus(
      long totalBytes,
      long usedBytes,
      long freeBytes,
      long usableBytes,
      double usagePercent,
      double freePercent,
      String total,
      String used,
      String free,
      String usable,
      List<DiskVolumeStatus> volumes) {
  }

  public record DiskVolumeStatus(
      String path,
      long totalBytes,
      long usedBytes,
      long freeBytes,
      long usableBytes,
      double usagePercent,
      double freePercent,
      String total,
      String used,
      String free,
      String usable) {
  }

  public record DatabaseStatus(
      String name,
      long sizeBytes,
      String size,
      long storeCount,
      long embeddingTableCount,
      List<EmbeddingTableStatus> embeddingTables) {
  }

  public record EmbeddingTableStatus(
      String schema,
      String table,
      UUID storeId,
      String storeName,
      String model,
      Integer embeddingDimensions,
      long tableSizeBytes,
      long indexesSizeBytes,
      long totalSizeBytes,
      long estimatedRows,
      String tableSize,
      String indexesSize,
      String totalSize) {
  }
}
