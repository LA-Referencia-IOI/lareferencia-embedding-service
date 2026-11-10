package org.lareferencia.embedding.v2.job;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Bounded worker runtime; claims and heartbeats use reserved coordination capacity. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class JobWorker implements SmartLifecycle {
  private static final Logger log=LoggerFactory.getLogger(JobWorker.class);
  private final JobRepository jobs;private final JobHandlers handlers;private final DvpProperties properties;
  private final String instance=UUID.randomUUID().toString();
  private final AtomicBoolean running=new AtomicBoolean();
  private ScheduledExecutorService scheduler;private ExecutorService executor;private Semaphore slots;
  public JobWorker(JobRepository jobs,JobHandlers handlers,DvpProperties properties) {
    this.jobs=jobs;this.handlers=handlers;this.properties=properties;
  }
  @Override public synchronized void start() {
    if(!properties.isWorkersEnabled() || properties.getRole()==DvpProperties.Role.API || running.get()) return;
    if(properties.getHeartbeatSeconds()>=properties.getLeaseSeconds())
      throw new IllegalStateException("Job heartbeat must be shorter than lease duration");
    if(properties.getWorkerConcurrency()>properties.getLockConnections())
      throw new IllegalStateException("Worker concurrency exceeds dedicated lock connection budget");
    slots=new Semaphore(properties.getWorkerConcurrency());
    scheduler=Executors.newScheduledThreadPool(properties.getWorkerConcurrency()+1,
        Thread.ofPlatform().name("dvp-job-coordination-",0).factory());
    executor=Executors.newFixedThreadPool(properties.getWorkerConcurrency(),
        Thread.ofPlatform().name("dvp-job-worker-",0).factory());
    running.set(true);
    scheduler.scheduleWithFixedDelay(this::poll,0,properties.getWorkerPollMillis(),TimeUnit.MILLISECONDS);
  }
  private void poll() {
    if(!running.get() || !slots.tryAcquire()) return;
    try {
      String owner=instance+":"+UUID.randomUUID();
      var lease=jobs.claim(owner,handlers.supportedKinds());
      if(lease.isEmpty()) { slots.release();return; }
      try { executor.execute(()->execute(lease.get())); }
      catch(RejectedExecutionException e) { jobs.release(lease.get(),1000);slots.release(); }
    } catch(RuntimeException e) {
      // Do not log database/provider messages, input text or credentials.
      log.warn("Job claim failed: {}",e.getClass().getSimpleName());slots.release();
    }
  }
  private void execute(JobRepository.Lease lease) {
    var heartbeat=scheduler.scheduleWithFixedDelay(()->{
      try { if(!jobs.heartbeat(lease)) log.warn("Job lease lost: {}",lease.jobId()); }
      catch(RuntimeException e) { log.warn("Job heartbeat unavailable: {}",e.getClass().getSimpleName()); }
    },properties.getHeartbeatSeconds(),properties.getHeartbeatSeconds(),TimeUnit.SECONDS);
    try {
      var job=jobs.find(lease.jobId()).orElseThrow(JobRepository.LeaseLostException::new);
      var handler=handlers.find(job.kind()).orElseThrow(JobRepository.LeaseLostException::new);
      handler.run(lease,jobs);
      if(!jobs.finishIfComplete(lease)) jobs.release(lease,jobs.nextAttemptDelay(lease));
    } catch(JobRepository.LeaseLostException e) {
      log.warn("Job lease lost: {}",lease.jobId());
    } catch(Exception e) {
      if(running.get() && !Thread.currentThread().isInterrupted()) {
        log.warn("Job failed with sanitized error: {} ({})",lease.jobId(),e.getClass().getSimpleName());
        try { jobs.fail(lease,"worker_error"); }
        catch(JobRepository.LeaseLostException ignored) { /* A new owner now controls the job. */ }
      }
    } finally { heartbeat.cancel(false);slots.release(); }
  }
  @Override public synchronized void stop() {
    if(!running.getAndSet(false)) return;
    executor.shutdownNow();scheduler.shutdownNow();
  }
  @Override public boolean isRunning() { return running.get(); }
}
