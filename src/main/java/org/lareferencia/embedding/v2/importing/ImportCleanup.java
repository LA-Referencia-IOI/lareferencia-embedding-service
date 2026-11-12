package org.lareferencia.embedding.v2.importing;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles only service-generated files while holding their filesystem and registry locks. */
@Component
@ConditionalOnProperty(name={"app.dvp.enabled","app.dvp.import-enabled"},havingValue="true")
public class ImportCleanup implements SmartLifecycle {
  private static final Logger log=LoggerFactory.getLogger(ImportCleanup.class);
  private final DurableImportStore files;private final JdbcTemplate jdbc;private final TransactionTemplate writes;private final DvpProperties properties;
  private final AtomicBoolean running=new AtomicBoolean();private ScheduledExecutorService scheduler;private String cursor;
  public ImportCleanup(DurableImportStore files,@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
      @Qualifier("writeTransactions") TransactionTemplate writes,DvpProperties properties) {
    this.files=files;this.jdbc=jdbc;this.writes=writes;this.properties=properties;
  }
  public synchronized int runBatch() {
    Instant cutoff=Instant.now().minus(properties.getTemporaryRetentionHours(),java.time.temporal.ChronoUnit.HOURS);
    var keys=files.staleKeys(cutoff,cursor,100);int removed=0;
    for(String key:keys) {
      boolean deleted=files.withStaleLock(key,cutoff,unlink->{
        if(key.endsWith(".part")){unlink.run();return true;}
        return Boolean.TRUE.equals(writes.execute(tx->{
          var rows=jdbc.query("SELECT job_id,expires_at IS NOT NULL AND expires_at<=clock_timestamp() FROM dvp.import_files WHERE storage_key=? FOR UPDATE",
              (rs,n)->MapEntry.of(rs.getObject(1,UUID.class),rs.getBoolean(2)),key);
          if(rows.isEmpty()){unlink.run();return true;}
          var row=rows.getFirst();if(!row.expired())return false;
          Boolean active=jdbc.queryForObject("""
              WITH RECURSIVE descendants AS (
                SELECT id,status FROM dvp.jobs WHERE id=?
                UNION ALL SELECT j.id,j.status FROM dvp.jobs j JOIN descendants d ON j.parent_job_id=d.id
              ) SELECT EXISTS(SELECT 1 FROM descendants WHERE status IN ('queued','running'))
              """,Boolean.class,row.job());
          if(Boolean.TRUE.equals(active))return false;
          unlink.run();return true;
        }));
      });
      if(deleted)removed++;
    }
    cursor=keys.size()<100?null:keys.getLast();return removed;
  }
  private record MapEntry(UUID job,boolean expired) {static MapEntry of(UUID job,boolean expired){return new MapEntry(job,expired);}}
  public synchronized void start() {
    if(running.get() || !properties.isWorkersEnabled() || properties.getRole()==DvpProperties.Role.API)return;
    scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("dvp-import-cleanup-",0).factory());running.set(true);
    scheduler.scheduleWithFixedDelay(()->{try{runBatch();}catch(RuntimeException e){log.warn("Import cleanup unavailable: {}",e.getClass().getSimpleName());}},
        properties.getImportCleanupIntervalMs(),properties.getImportCleanupIntervalMs(),TimeUnit.MILLISECONDS);
  }
  public synchronized void stop(){if(running.getAndSet(false))scheduler.shutdownNow();}
  public boolean isRunning(){return running.get();}
}
