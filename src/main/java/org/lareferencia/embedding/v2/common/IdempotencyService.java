package org.lareferencia.embedding.v2.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name="app.dvp.enabled", havingValue="true")
public class IdempotencyService {
  public record Result(int status, JsonNode body, Map<String,String> headers, UUID jobId) {}
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private final DvpProperties properties;
  public IdempotencyService(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,
      @Qualifier("writeTransactions") TransactionTemplate transactions,ObjectMapper mapper,DvpProperties properties) {
    this.jdbc=jdbc; this.transactions=transactions; this.mapper=mapper; this.properties=properties;
  }

  public Result execute(String scope,String key,String requestHash,Supplier<Result> write) {
    if (key==null || key.isBlank() || key.codePointCount(0,key.length())>200 || key.indexOf(0)>=0) {
      throw V2Exception.badRequest("A nonblank Idempotency-Key of at most 200 characters is required");
    }
    if (scope==null || scope.length()>200 || !requestHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid internal idempotency identity");
    }
    return transactions.execute(status -> {
        // The complete response and mutation share this connection and commit.
        acquireRequestLock(scope,key);
        jdbc.update("""
            DELETE FROM dvp.idempotency_requests r
            WHERE scope=? AND request_key=? AND created_at < now() - (? * interval '1 day')
              AND (job_id IS NULL OR EXISTS (SELECT 1 FROM dvp.jobs j WHERE j.id=r.job_id
                   AND j.status IN ('succeeded','completed_with_errors','failed')))
            """,scope,key,properties.getIdempotencyRetentionDays());
        var prior=jdbc.query("""
            SELECT request_hash,response_status,response_body::text,response_headers::text,job_id
            FROM dvp.idempotency_requests WHERE scope=? AND request_key=?
            """, (rs,row) -> {
              if (!requestHash.equals(rs.getString(1))) {
                throw new V2Exception(409,"idempotency_conflict","Idempotency-Key was used with different content");
              }
              try {
                JsonNode headerNode=mapper.readTree(rs.getString(4));
                var headers=new LinkedHashMap<String,String>();
                headerNode.fields().forEachRemaining(e -> headers.put(e.getKey(),e.getValue().asText()));
                return new Result(rs.getInt(2),mapper.readTree(rs.getString(3)),headers,rs.getObject(5,UUID.class));
              } catch(java.io.IOException e) { throw new IllegalStateException("Invalid stored response",e); }
            },scope,key);
        if (!prior.isEmpty()) return prior.getFirst();
        Result result=write.get();
        try {
          jdbc.update("""
              INSERT INTO dvp.idempotency_requests
              (scope,request_key,request_hash,response_status,response_body,response_headers,job_id)
              VALUES (?,?,?,?,?::jsonb,?::jsonb,?)
              """,scope,key,requestHash,result.status(),mapper.writeValueAsString(result.body()),
              mapper.writeValueAsString(result.headers()),result.jobId());
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot persist response",e); }
        return result;
    });
  }

  private void acquireRequestLock(String scope,String key) {
    jdbc.execute("SET LOCAL lock_timeout='5s'");
    try {
      jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> {}, lockId(scope,key));
    } catch(DataAccessException e) {
      // Only failure of this lock means a request with the same key is in progress.
      // Throwing exits the callback and rolls back the aborted transaction.
      for (Throwable cause=e;cause!=null;cause=cause.getCause()) {
        if (cause instanceof PSQLException pg && "55P03".equals(pg.getSQLState())) {
          throw new V2Exception(409,"idempotency_in_progress","Another request with this key is still in progress");
        }
      }
      throw e;
    }
    jdbc.execute("SET LOCAL lock_timeout=DEFAULT");
  }

  public static long lockId(String scope,String key) {
    try {
      byte[] digest=java.security.MessageDigest.getInstance("SHA-256")
          .digest((scope+"\u0000"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.nio.ByteBuffer.wrap(digest).getLong();
    } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  /** Bounded cleanup; an active job's key is retained regardless of age. */
  public int purgeExpired(int batchSize) {
    if (batchSize<1 || batchSize>1000) throw new IllegalArgumentException("Invalid cleanup batch size");
    return transactions.execute(status -> jdbc.update("""
        WITH expired AS (
          SELECT r.scope,r.request_key FROM dvp.idempotency_requests r
          WHERE r.created_at < now() - (? * interval '1 day')
            AND (r.job_id IS NULL OR EXISTS (SELECT 1 FROM dvp.jobs j WHERE j.id=r.job_id
                 AND j.status IN ('succeeded','completed_with_errors','failed')))
          ORDER BY r.created_at LIMIT ? FOR UPDATE OF r SKIP LOCKED
        ) DELETE FROM dvp.idempotency_requests r USING expired e
          WHERE r.scope=e.scope AND r.request_key=e.request_key
        """,properties.getIdempotencyRetentionDays(),batchSize));
  }
}
