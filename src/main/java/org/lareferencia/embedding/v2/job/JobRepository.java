package org.lareferencia.embedding.v2.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL queue. Every claim/progress operation checks a committed, unexpired fencing lease. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class JobRepository {
  public record Job(UUID id,UUID collectionId,UUID indexationId,UUID parentJobId,String kind,String status,
      JsonNode parameters,JsonNode checkpoint,JsonNode counters,Instant createdAt,Instant finishedAt,
      String errorCode,String leaseOwner,Instant leaseUntil,long fencingToken,int attempts) { }
  public record Lease(UUID jobId,String owner,long token) { }
  public record ItemLease(UUID jobId,String itemKey,UUID documentId,JsonNode locator,String owner,long token,int attempts) { }
  public record ItemError(String itemKey,UUID documentId,JsonNode locator,String code,int attempts) { }

  private static final Set<String> COUNTERS=Set.of("documents_read","documents_processed","documents_failed",
      "units_prepared","vectors_generated","vectors_imported","vectors_reused","items_skipped","retries");
  private final JdbcTemplate write,read,coord;
  private final TransactionTemplate writes,coordTransactions;
  private final DataSource lockDataSource;
  private final ObjectMapper mapper;
  private final DvpProperties properties;

  public JobRepository(@Qualifier("jdbcTemplate") JdbcTemplate write,@Qualifier("readJdbc") JdbcTemplate read,
      @Qualifier("coordinationJdbc") JdbcTemplate coord,
      @Qualifier("writeTransactions") TransactionTemplate writes,
      @Qualifier("coordinationTransactions") TransactionTemplate coordTransactions,
      @Qualifier("lockDataSource") DataSource lockDataSource,ObjectMapper mapper,DvpProperties properties) {
    this.write=write;this.read=read;this.coord=coord;this.writes=writes;this.coordTransactions=coordTransactions;
    this.lockDataSource=lockDataSource;this.mapper=mapper;this.properties=properties;
  }

  private JsonNode json(String value) {
    try { return mapper.readTree(value); }
    catch(java.io.IOException e) { throw new IllegalStateException("Invalid stored job state"); }
  }
  private String json(JsonNode value) {
    try { return mapper.writeValueAsString(value); }
    catch(java.io.IOException e) { throw new IllegalArgumentException("Invalid job data"); }
  }
  private Job row(java.sql.ResultSet rs) throws SQLException {
    var finished=rs.getTimestamp("finished_at");var until=rs.getTimestamp("lease_until");
    return new Job(rs.getObject("id",UUID.class),rs.getObject("collection_id",UUID.class),
        rs.getObject("indexation_id",UUID.class),rs.getObject("parent_job_id",UUID.class),rs.getString("kind"),
        rs.getString("status"),json(rs.getString("parameters")),json(rs.getString("checkpoint")),
        json(rs.getString("counters")),rs.getTimestamp("created_at").toInstant(),
        finished==null?null:finished.toInstant(),rs.getString("global_error_code"),rs.getString("lease_owner"),
        until==null?null:until.toInstant(),rs.getLong("fencing_token"),rs.getInt("attempts"));
  }
  public Optional<Job> find(UUID id) {
    return write.query("SELECT * FROM dvp.jobs WHERE id=?",(rs,n)->row(rs),id).stream().findFirst();
  }
  public Optional<Job> findForRead(UUID id) {
    return read.query("SELECT * FROM dvp.jobs WHERE id=?",(rs,n)->row(rs),id).stream().findFirst();
  }
  public List<Job> listForRead(UUID after,int limit) {
    return after==null
        ? read.query("SELECT * FROM dvp.jobs ORDER BY id LIMIT ?",(rs,n)->row(rs),limit)
        : read.query("SELECT * FROM dvp.jobs WHERE id>? ORDER BY id LIMIT ?",(rs,n)->row(rs),after,limit);
  }
  public Job lock(UUID id) {
    return write.query("SELECT * FROM dvp.jobs WHERE id=? FOR UPDATE",(rs,n)->row(rs),id).stream().findFirst()
        .orElseThrow(()->new V2Exception(404,"job_not_found","Job does not exist"));
  }
  /** Called inside the idempotency write transaction. */
  public Job insert(UUID id,UUID collectionId,UUID indexationId,UUID parent,String kind,JsonNode parameters) {
    if (!Set.of("import","prepare","generate","maintenance").contains(kind) || !parameters.isObject())
      throw V2Exception.badRequest("Invalid job kind or parameters");
    if (!Boolean.TRUE.equals(write.queryForObject("SELECT EXISTS(SELECT 1 FROM dvp.collections WHERE id=?)",Boolean.class,collectionId)))
      throw new V2Exception(404,"collection_not_found","Collection does not exist");
    if(indexationId!=null && !Boolean.TRUE.equals(write.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM dvp.indexations WHERE id=? AND collection_id=?)",Boolean.class,indexationId,collectionId)))
      throw new V2Exception(404,"indexation_not_found","Index configuration does not exist in collection");
    write.update("INSERT INTO dvp.jobs(id,collection_id,indexation_id,parent_job_id,kind,status,parameters) VALUES (?,?,?,?,?,'queued',?::jsonb)",
        id,collectionId,indexationId,parent,kind,json(parameters));
    return find(id).orElseThrow();
  }
  /** Atomic job claim restricted to handlers actually installed in this process. */
  public Optional<Lease> claim(String owner,Set<String> supportedKinds) {
    if(owner==null || owner.isBlank() || owner.length()>200 || supportedKinds.isEmpty()) return Optional.empty();
    var allowed=supportedKinds.stream().filter(Set.of("import","prepare","generate","maintenance")::contains).sorted().toList();
    if(allowed.isEmpty()) return Optional.empty();
    String placeholders=String.join(",",Collections.nCopies(allowed.size(),"?"));
    List<Object> args=new ArrayList<>(allowed);args.add(owner);args.add(properties.getLeaseSeconds());
    return coordTransactions.execute(tx -> coord.query("""
        WITH candidate AS (
          SELECT id FROM dvp.jobs WHERE kind IN (%s) AND available_at <= now()
            AND (status='queued' OR (status='running' AND lease_until <= clock_timestamp()))
          ORDER BY available_at,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
        ) UPDATE dvp.jobs j SET status='running',lease_owner=?,lease_until=clock_timestamp()+(? * interval '1 second'),
            fencing_token=j.fencing_token+1,attempts=j.attempts+1
          FROM candidate c WHERE j.id=c.id RETURNING j.id,j.lease_owner,j.fencing_token
        """.formatted(placeholders),(rs,n)->new Lease(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3)),
        args.toArray()).stream().findFirst());
  }
  public boolean heartbeat(Lease lease) {
    return Boolean.TRUE.equals(coordTransactions.execute(tx -> {
      int updated=coord.update("""
          UPDATE dvp.jobs SET lease_until=clock_timestamp()+(? * interval '1 second')
          WHERE id=? AND status='running' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()
          """,properties.getLeaseSeconds(),lease.jobId(),lease.owner(),lease.token());
      if(updated==1) coord.update("""
          UPDATE dvp.job_items SET lease_until=clock_timestamp()+(? * interval '1 second')
          WHERE job_id=? AND status='running' AND lease_owner=? AND lease_until>clock_timestamp()
          """,properties.getLeaseSeconds(),lease.jobId(),lease.owner());
      return updated==1;
    }));
  }
  private void requireLease(Lease lease) {
    Boolean valid=write.query("SELECT status='running' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp() FROM dvp.jobs WHERE id=? FOR UPDATE",
        rs->rs.next() && rs.getBoolean(1),lease.owner(),lease.token(),lease.jobId());
    if(!Boolean.TRUE.equals(valid)) throw new LeaseLostException();
  }
  private void requireItem(Lease job,ItemLease item) {
    if(!job.jobId().equals(item.jobId()) || !job.owner().equals(item.owner())) throw new LeaseLostException();
    Boolean valid=write.query("""
        SELECT status='running' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()
        FROM dvp.job_items WHERE job_id=? AND item_key=? FOR UPDATE
        """,rs->rs.next() && rs.getBoolean(1),item.owner(),item.token(),item.jobId(),item.itemKey());
    if(!Boolean.TRUE.equals(valid)) throw new LeaseLostException();
  }
  /** Capture a stable upper sequence bound under the current lease, without loading the collection. */
  public void initializeEnumeration(Lease lease) {
    writes.executeWithoutResult(tx->{
      requireLease(lease);
      write.update("""
          UPDATE dvp.jobs SET checkpoint=jsonb_build_object('high_water',
            COALESCE((SELECT max(sequence_id) FROM dvp.documents WHERE collection_id=dvp.jobs.collection_id),0),
            'after',0,'exhausted',false) WHERE id=? AND NOT jsonb_exists(checkpoint,'high_water') AND NOT jsonb_exists(checkpoint,'retry')
          """,lease.jobId());
    });
  }
  /** Initialize retry traversal inside the child-creation transaction; failed items remain in the parent. */
  public void initializeRetry(UUID child) {
    write.update("UPDATE dvp.jobs SET checkpoint=jsonb_build_object('retry',true,'after','','exhausted',false) WHERE id=?",
        child);
  }

  /** Bounded keyset materialization and checkpoint advancement commit together. */
  public int materialize(Lease lease) {
    return Objects.requireNonNull(writes.execute(tx->{
      requireLease(lease);
      Job job=lock(lease.jobId());
      if(!job.checkpoint().has("high_water") && !job.checkpoint().path("retry").asBoolean())
        throw new IllegalStateException("Enumeration not initialized");
      if(job.checkpoint().path("exhausted").asBoolean()) return 0;
      int active=write.queryForObject("SELECT count(*) FROM dvp.job_items WHERE job_id=? AND status IN ('queued','running')",
          Integer.class,lease.jobId());
      int size=Math.min(Math.min(properties.getDocumentBatchSize(),100),Math.min(properties.getMaxActiveItems(),1000)-active);
      if(size<=0) return 0;
      if(job.checkpoint().path("retry").asBoolean()) return materializeRetry(job,size);
      long after=job.checkpoint().path("after").asLong(),high=job.checkpoint().path("high_water").asLong();
      var rows=write.query("""
          SELECT id,sequence_id FROM dvp.documents WHERE collection_id=? AND sequence_id>? AND sequence_id<=?
          ORDER BY sequence_id LIMIT ?
          """,(rs,n)->Map.entry(rs.getObject(1,UUID.class),rs.getLong(2)),job.collectionId(),after,high,size);
      for(var row:rows) write.update("""
          INSERT INTO dvp.job_items(job_id,item_key,document_id,status,locator)
          VALUES (?,?,?,'queued',jsonb_build_object('sequence_id',?)) ON CONFLICT (job_id,item_key) DO NOTHING
          """,lease.jobId(),row.getKey().toString(),row.getKey(),row.getValue());
      long next=rows.isEmpty()?after:rows.getLast().getValue();
      write.update("""
          UPDATE dvp.jobs SET checkpoint=jsonb_set(jsonb_set(checkpoint,'{after}',to_jsonb(?::bigint)),
            '{exhausted}',to_jsonb(?::boolean)) WHERE id=?
          """,next,rows.size()<size || next>=high,lease.jobId());
      return rows.size();
    }));
  }
  private int materializeRetry(Job job,int size) {
    if(job.parentJobId()==null) throw new IllegalStateException("Retry parent unavailable");
    String after=job.checkpoint().path("after").asText();
    var rows=write.query("""
        SELECT item_key,document_id,locator::text FROM dvp.job_items
        WHERE job_id=? AND status='failed' AND item_key COLLATE "C" > ?::text COLLATE "C"
        ORDER BY item_key COLLATE "C" LIMIT ?
        """,(rs,n)->new ItemError(rs.getString(1),rs.getObject(2,UUID.class),json(rs.getString(3)),null,0),
        job.parentJobId(),after,size);
    for(var row:rows) write.update("""
        INSERT INTO dvp.job_items(job_id,item_key,document_id,status,locator)
        VALUES (?,?,?,'queued',?::jsonb) ON CONFLICT (job_id,item_key) DO NOTHING
        """,job.id(),row.itemKey(),row.documentId(),json(row.locator()));
    String next=rows.isEmpty()?after:rows.getLast().itemKey();
    write.update("""
        UPDATE dvp.jobs SET checkpoint=jsonb_set(jsonb_set(checkpoint,'{after}',to_jsonb(?::text)),
          '{exhausted}',to_jsonb(?::boolean)) WHERE id=?
        """,next,rows.size()<size,job.id());
    return rows.size();
  }

  /** Claims one due item only while its parent lease is valid. */
  public Optional<ItemLease> claimItem(Lease job) {
    return coordTransactions.execute(tx -> coord.query("""
        WITH candidate AS (
          SELECT i.job_id,i.item_key FROM dvp.job_items i JOIN dvp.jobs j ON j.id=i.job_id
          WHERE j.id=? AND j.status='running' AND j.lease_owner=? AND j.fencing_token=? AND j.lease_until>clock_timestamp()
            AND i.next_attempt_at<=now() AND (i.status='queued' OR (i.status='running' AND i.lease_until<=clock_timestamp()))
          ORDER BY i.item_key LIMIT 1 FOR UPDATE OF i SKIP LOCKED
        ) UPDATE dvp.job_items i SET status='running',lease_owner=?,lease_until=clock_timestamp()+(? * interval '1 second'),
            fencing_token=i.fencing_token+1,attempts=i.attempts+1 FROM candidate c
          WHERE i.job_id=c.job_id AND i.item_key=c.item_key
          RETURNING i.job_id,i.item_key,i.document_id,i.locator::text,i.lease_owner,i.fencing_token,i.attempts
        """,(rs,n)->new ItemLease(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),
          json(rs.getString(4)),rs.getString(5),rs.getLong(6),rs.getInt(7)),job.jobId(),job.owner(),job.token(),
        job.owner(),properties.getLeaseSeconds()).stream().findFirst());
  }
  /** Commit domain writes, item terminal state, counters and optional checkpoint in one fenced transaction. */
  public void complete(Lease job,ItemLease item,String result,String errorCode,Map<String,Long> increments,
                       JsonNode checkpoint,Consumer<JdbcTemplate> domainWrite) {
    if(!Set.of("succeeded","failed","skipped").contains(result)) throw new IllegalArgumentException("Invalid result");
    if(errorCode!=null && !errorCode.matches("[a-z][a-z0-9_]{0,79}")) throw new IllegalArgumentException("Invalid error code");
    if("failed".equals(result) && errorCode==null) throw new IllegalArgumentException("Error code required");
    if(!COUNTERS.containsAll(increments.keySet()) || increments.values().stream().anyMatch(v->v==null || v<0))
      throw new IllegalArgumentException("Invalid counters");
    if(checkpoint!=null && !checkpoint.isObject()) throw new IllegalArgumentException("Invalid checkpoint");
    writes.executeWithoutResult(tx->{
      requireLease(job);requireItem(job,item);
      domainWrite.accept(write);
      // Recheck expiry after domain work, before any state/counter update.
      requireLease(job);requireItem(job,item);
      write.update("UPDATE dvp.job_items SET status=?,error_code=?,error_details=NULL,lease_owner=NULL,lease_until=NULL WHERE job_id=? AND item_key=?",
          result,errorCode,job.jobId(),item.itemKey());
      for(var entry:increments.entrySet()) write.update("""
          UPDATE dvp.jobs SET counters=jsonb_set(counters,ARRAY[?],
            to_jsonb(COALESCE((counters->>?)::bigint,0)+?::bigint),true) WHERE id=?
          """,entry.getKey(),entry.getKey(),entry.getValue(),job.jobId());
      if(checkpoint!=null) write.update("UPDATE dvp.jobs SET checkpoint=?::jsonb WHERE id=?",json(checkpoint),job.jobId());
    });
  }
  /** For generation handlers: refuse publication if the dedicated advisory session is unavailable. */
  public void completeWithAdvisoryLock(Lease job,ItemLease item,String result,String errorCode,
      Map<String,Long> increments,JsonNode checkpoint,AdvisoryLock lock,Consumer<JdbcTemplate> domainWrite) {
    Objects.requireNonNull(lock).requireAvailable();
    if(!Objects.equals(lock.document,item.documentId())) throw new IllegalArgumentException("Wrong advisory lock document");
    complete(job,item,result,errorCode,increments,checkpoint,jdbc->{
      Job current=find(job.jobId()).orElseThrow(LeaseLostException::new);
      if(!Objects.equals(lock.indexation,current.indexationId())) throw new IllegalArgumentException("Wrong advisory lock indexation");
      lock.requireAvailable();domainWrite.accept(jdbc);lock.requireAvailable();
    });
  }

  /** Classify a provider failure and persist either a due time or a permanent item error. */
  public void recordFailure(Lease job,ItemLease item,String errorCode,Integer status,boolean transportFailure,
                            String retryAfter) {
    var delay=RetryPolicy.delayMillis(item.attempts(),status,transportFailure,retryAfter,
        java.time.Clock.systemUTC(),java.util.concurrent.ThreadLocalRandom.current());
    if(delay.isPresent()) retryOrFail(job,item,errorCode,delay.getAsLong());
    else complete(job,item,"failed",errorCode,Map.of("documents_failed",1L),null,jdbc->{});
  }

  /** Persist backoff; a worker thread is never kept asleep for a delayed retry. */
  public void retryOrFail(Lease job,ItemLease item,String errorCode,long delayMillis) {
    if(errorCode==null || !errorCode.matches("[a-z][a-z0-9_]{0,79}") || delayMillis<0 || delayMillis>300_000)
      throw new IllegalArgumentException("Invalid retry");
    if(item.attempts()>=5) {
      complete(job,item,"failed",errorCode,Map.of("documents_failed",1L),null,jdbc->{});
      return;
    }
    writes.executeWithoutResult(tx->{
      requireLease(job);requireItem(job,item);
      write.update("""
          UPDATE dvp.job_items SET status='queued',lease_owner=NULL,lease_until=NULL,error_code=?,
            next_attempt_at=now()+(? * interval '1 millisecond') WHERE job_id=? AND item_key=?
          """,errorCode,delayMillis,job.jobId(),item.itemKey());
      write.update("UPDATE dvp.jobs SET counters=jsonb_set(counters,'{retries}',to_jsonb(COALESCE((counters->>'retries')::bigint,0)+1),true) WHERE id=?",job.jobId());
    });
  }
  /** Terminal state is possible only when enumeration is exhausted and no active items remain. */
  public boolean finishIfComplete(Lease job) {
    return Boolean.TRUE.equals(writes.execute(tx->{
      requireLease(job);
      return write.update("""
          UPDATE dvp.jobs j SET status=CASE WHEN EXISTS (SELECT 1 FROM dvp.job_items i WHERE i.job_id=j.id AND i.status='failed')
            THEN 'completed_with_errors' ELSE 'succeeded' END,finished_at=now(),lease_owner=NULL,lease_until=NULL
          WHERE j.id=? AND COALESCE((j.checkpoint->>'exhausted')::boolean,false)
            AND NOT EXISTS (SELECT 1 FROM dvp.job_items i WHERE i.job_id=j.id AND i.status IN ('queued','running'))
          """,job.jobId())==1;
    }));
  }
  public void release(Lease job,long delayMillis) {
    if(delayMillis<0 || delayMillis>300_000) throw new IllegalArgumentException("Invalid delay");
    coord.update("""
        UPDATE dvp.jobs SET status='queued',lease_owner=NULL,lease_until=NULL,
          available_at=now()+(? * interval '1 millisecond')
        WHERE id=? AND status='running' AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()
        """,delayMillis,job.jobId(),job.owner(),job.token());
  }
  /** Record a sanitized global infrastructure failure, guarded by the current job lease. */
  public void fail(Lease job,String errorCode) {
    if(errorCode==null || !errorCode.matches("[a-z][a-z0-9_]{0,79}")) throw new IllegalArgumentException("Invalid error code");
    writes.executeWithoutResult(tx->{
      requireLease(job);
      write.update("UPDATE dvp.jobs SET status='failed',global_error_code=?,finished_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL WHERE id=?",
          errorCode,job.jobId());
    });
  }

  /** Milliseconds until the earliest delayed item is eligible; capped to avoid a sleeping worker. */
  public long nextAttemptDelay(Lease job) {
    Long delay=coord.queryForObject("""
        SELECT COALESCE(GREATEST(0,(EXTRACT(EPOCH FROM min(CASE WHEN status='queued' THEN next_attempt_at ELSE lease_until END)-clock_timestamp())*1000)::bigint),1000)
        FROM dvp.job_items WHERE job_id=? AND status IN ('queued','running')
        """,Long.class,job.jobId());
    return Math.min(300_000,delay==null?1000:delay);
  }

  public List<ItemError> errors(UUID id,String after,int limit) {
    return errors(write,id,after,limit);
  }
  public List<ItemError> errorsForRead(UUID id,String after,int limit) {
    return errors(read,id,after,limit);
  }
  private List<ItemError> errors(JdbcTemplate jdbc,UUID id,String after,int limit) {
    return jdbc.query("""
        SELECT item_key,document_id,locator::text,error_code,attempts FROM dvp.job_items
        WHERE job_id=? AND status='failed' AND (?::text IS NULL OR item_key COLLATE "C" > ?::text COLLATE "C")
        ORDER BY item_key COLLATE "C" LIMIT ?
        """,(rs,n)->new ItemError(rs.getString(1),rs.getObject(2,UUID.class),json(rs.getString(3)),
            rs.getString(4),rs.getInt(5)),id,after,after,limit);
  }
  /** Dedicated session advisory lock, held outside the write transaction and provider calls. */
  public Optional<AdvisoryLock> tryDocumentLock(UUID indexation,UUID document) {
    Connection connection=null;
    try {
      connection=lockDataSource.getConnection();
      long id=org.lareferencia.embedding.v2.common.IdempotencyService.lockId(indexation.toString(),document.toString());
      try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
        statement.setLong(1,id);
        try(var rs=statement.executeQuery()) {
          rs.next();
          if(!rs.getBoolean(1)) { connection.close();return Optional.empty(); }
        }
      }
      return Optional.of(new AdvisoryLock(connection,id,indexation,document));
    } catch(SQLException e) {
      if(connection!=null) try { connection.close(); } catch(SQLException ignored) { }
      throw new V2Exception(503,"database_unavailable","Advisory lock unavailable");
    }
  }
  public static final class AdvisoryLock implements AutoCloseable {
    private final Connection connection;private final long id;private final UUID indexation;private final UUID document;
    AdvisoryLock(Connection connection,long id,UUID indexation,UUID document) {
      this.connection=connection;this.id=id;this.indexation=indexation;this.document=document;
    }
    public void requireAvailable() {
      try { if(connection.isClosed() || !connection.isValid(1)) throw new LeaseLostException(); }
      catch(SQLException e) { throw new LeaseLostException(); }
    }
    @Override public void close() {
      try(var statement=connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
        statement.setLong(1,id);statement.execute();
      } catch(SQLException ignored) { /* A lost session releases its advisory locks. */ }
      finally { try { connection.close(); } catch(SQLException ignored) { /* Connection already unusable. */ } }
    }
  }
  public static final class LeaseLostException extends RuntimeException {
    public LeaseLostException() { super("Job or item lease is no longer valid"); }
  }
}
