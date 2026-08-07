package org.lareferencia.embedding.service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.lareferencia.embedding.config.PgVectorStoreProperties;
import org.lareferencia.embedding.dto.StatusResponse;
import org.lareferencia.embedding.entity.StoreEntity;
import org.lareferencia.embedding.repository.StatusRepository;
import org.lareferencia.embedding.repository.StoreRepository;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StatusService {

  private final StatusRepository statusRepository;
  private final StoreRepository storeRepository;
  private final StoreTableNamingService tableNamingService;
  private final PgVectorStoreProperties pgVectorStoreProperties;

  public StatusResponse status() {
    List<StoreEntity> stores = storeRepository.findAll();
    Map<String, StoreEntity> storesByTableName = stores.stream()
        .collect(Collectors.toMap(store -> tableNamingService.tableName(store.getId()), Function.identity()));
    List<StatusResponse.EmbeddingTableStatus> embeddingTables = statusRepository
        .embeddingTableStats(pgVectorStoreProperties.getSchemaName()).stream()
        .map(tableStats -> toEmbeddingTableStatus(tableStats, storesByTableName.get(tableStats.tableName())))
        .toList();
    long databaseSizeBytes = statusRepository.currentDatabaseSizeBytes();

    return new StatusResponse(
        Instant.now(),
        systemStatus(),
        new StatusResponse.DatabaseStatus(
            statusRepository.currentDatabaseName(),
            databaseSizeBytes,
            formatBytes(databaseSizeBytes),
            statusRepository.storeCount(),
            embeddingTables.size(),
            embeddingTables));
  }

  private StatusResponse.EmbeddingTableStatus toEmbeddingTableStatus(
      StatusRepository.EmbeddingTableStats tableStats,
      StoreEntity store) {
    return new StatusResponse.EmbeddingTableStatus(
        tableStats.schemaName(),
        tableStats.tableName(),
        Optional.ofNullable(store).map(StoreEntity::getId).orElse(null),
        Optional.ofNullable(store).map(StoreEntity::getName).orElse(null),
        Optional.ofNullable(store).map(StoreEntity::getModel).orElse(null),
        Optional.ofNullable(store).map(StoreEntity::getEmbeddingDimension).orElse(null),
        tableStats.tableSizeBytes(),
        tableStats.indexesSizeBytes(),
        tableStats.totalSizeBytes(),
        tableStats.estimatedRows(),
        formatBytes(tableStats.tableSizeBytes()),
        formatBytes(tableStats.indexesSizeBytes()),
        formatBytes(tableStats.totalSizeBytes()));
  }

  private StatusResponse.SystemStatus systemStatus() {
    return new StatusResponse.SystemStatus(cpuStatus(), memoryStatus(), diskStatus());
  }

  private StatusResponse.CpuStatus cpuStatus() {
    java.lang.management.OperatingSystemMXBean standardBean = ManagementFactory.getOperatingSystemMXBean();
    Double usagePercent = null;
    if (standardBean instanceof com.sun.management.OperatingSystemMXBean systemBean) {
      usagePercent = percentageOrNull(systemBean.getCpuLoad());
    }
    Double freePercent = usagePercent == null ? null : roundPercent(100 - usagePercent);
    return new StatusResponse.CpuStatus(
        standardBean.getAvailableProcessors(),
        usagePercent,
        freePercent,
        standardBean.getSystemLoadAverage());
  }

  private StatusResponse.MemoryStatus memoryStatus() {
    java.lang.management.OperatingSystemMXBean standardBean = ManagementFactory.getOperatingSystemMXBean();
    long totalBytes = Runtime.getRuntime().maxMemory();
    long freeBytes = Runtime.getRuntime().freeMemory();
    if (standardBean instanceof com.sun.management.OperatingSystemMXBean systemBean) {
      totalBytes = systemBean.getTotalMemorySize();
      freeBytes = systemBean.getFreeMemorySize();
    }
    long usedBytes = Math.max(0, totalBytes - freeBytes);
    return new StatusResponse.MemoryStatus(
        totalBytes,
        usedBytes,
        freeBytes,
        percentage(usedBytes, totalBytes),
        percentage(freeBytes, totalBytes),
        formatBytes(totalBytes),
        formatBytes(usedBytes),
        formatBytes(freeBytes));
  }

  private StatusResponse.DiskStatus diskStatus() {
    File[] roots = File.listRoots();
    List<StatusResponse.DiskVolumeStatus> volumes = (roots == null ? List.<File>of() : List.of(roots)).stream()
        .filter(root -> root.getTotalSpace() > 0)
        .sorted(Comparator.comparing(File::getAbsolutePath))
        .map(this::diskVolumeStatus)
        .toList();
    long totalBytes = volumes.stream().mapToLong(StatusResponse.DiskVolumeStatus::totalBytes).sum();
    long freeBytes = volumes.stream().mapToLong(StatusResponse.DiskVolumeStatus::freeBytes).sum();
    long usableBytes = volumes.stream().mapToLong(StatusResponse.DiskVolumeStatus::usableBytes).sum();
    long usedBytes = Math.max(0, totalBytes - freeBytes);
    return new StatusResponse.DiskStatus(
        totalBytes,
        usedBytes,
        freeBytes,
        usableBytes,
        percentage(usedBytes, totalBytes),
        percentage(freeBytes, totalBytes),
        formatBytes(totalBytes),
        formatBytes(usedBytes),
        formatBytes(freeBytes),
        formatBytes(usableBytes),
        volumes);
  }

  private StatusResponse.DiskVolumeStatus diskVolumeStatus(File root) {
    long totalBytes = root.getTotalSpace();
    long freeBytes = root.getFreeSpace();
    long usableBytes = root.getUsableSpace();
    long usedBytes = Math.max(0, totalBytes - freeBytes);
    return new StatusResponse.DiskVolumeStatus(
        root.getAbsolutePath(),
        totalBytes,
        usedBytes,
        freeBytes,
        usableBytes,
        percentage(usedBytes, totalBytes),
        percentage(freeBytes, totalBytes),
        formatBytes(totalBytes),
        formatBytes(usedBytes),
        formatBytes(freeBytes),
        formatBytes(usableBytes));
  }

  private Double percentageOrNull(double ratio) {
    if (Double.isNaN(ratio) || ratio < 0) {
      return null;
    }
    return roundPercent(ratio * 100);
  }

  private double percentage(long value, long total) {
    if (total <= 0) {
      return 0;
    }
    return roundPercent((double) value * 100 / total);
  }

  private double roundPercent(double value) {
    return Math.round(value * 100.0d) / 100.0d;
  }

  private String formatBytes(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    String[] units = { "KiB", "MiB", "GiB", "TiB", "PiB" };
    double value = bytes;
    int unitIndex = -1;
    do {
      value = value / 1024;
      unitIndex++;
    } while (value >= 1024 && unitIndex < units.length - 1);
    return "%.2f %s".formatted(value, units[unitIndex]);
  }
}
