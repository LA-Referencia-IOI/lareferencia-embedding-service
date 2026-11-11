package org.lareferencia.embedding.v2.integration;

import org.lareferencia.embedding.integration.TestDatabase;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.v2.common.*;
import org.lareferencia.embedding.v2.config.VectorCapabilityCheck;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true",
    "app.dvp.read-connections=1","app.dvp.coordination-connections=1","app.dvp.lock-connections=1",
    "app.dvp.connection-timeout-ms=300","app.dvp.inference-concurrency=1","app.dvp.workers-enabled=false"})
class FoundationIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url=TestDatabase.create();
    r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");
    r.add("spring.datasource.password",()->"");
  }
  @Autowired IdempotencyService idempotency;
  @Autowired ObjectMapper mapper;
  @Autowired @Qualifier("jdbcTemplate") JdbcTemplate write;
  @Autowired @Qualifier("coordinationJdbc") JdbcTemplate coordination;
  @Autowired @Qualifier("readDataSource") HikariDataSource reads;
  @Autowired @Qualifier("dataSource") DataSource writes;
  @Autowired ThreadPoolExecutor inferenceExecutor;

  private IdempotencyService.Result insert(String slug,UUID job) {
    UUID id=UUID.randomUUID();
    write.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",id,slug,"Display");
    if(job!=null) write.update("INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters) VALUES (?,?,'prepare','queued','{}')",job,id);
    return new IdempotencyService.Result(201,mapper.valueToTree(Map.of("id",id)),Map.of("Location","/collections/"+id),job);
  }
  @Test void concurrentSameKeyReplaysAtomicResponseAndDifferentContentConflicts() throws Exception {
    String key=UUID.randomUUID().toString(),scope="POST /foundation";
    var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var calls=new AtomicInteger();
    try(var executor=Executors.newFixedThreadPool(2)) {
      var first=executor.submit(()->idempotency.execute(scope,key,"a".repeat(64),()->{
        calls.incrementAndGet(); var result=insert("idem-"+key,null); entered.countDown();
        try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException(); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        return result;
      }));
      assertTrue(entered.await(5,TimeUnit.SECONDS));
      var second=executor.submit(()->idempotency.execute(scope,key,"a".repeat(64),()->{
        fail("Replay must not repeat write");return null;
      }));
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->
          write.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",Integer.class)>0);
      release.countDown();
      assertEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));assertEquals(1,calls.get());
    } finally { release.countDown(); }
    assertEquals("idempotency_conflict",assertThrows(V2Exception.class,()->
        idempotency.execute(scope,key,"b".repeat(64),()->null)).code());
    assertEquals(1,write.queryForObject("SELECT count(*) FROM dvp.collections WHERE slug=?",Integer.class,"idem-"+key));
  }
  @Test void lockWaitIsBoundedAndDoesNotLeaveReservation() throws Exception {
    String key=UUID.randomUUID().toString(),scope="POST /blocked";
    try(Connection connection=writes.getConnection();var statement=connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
      statement.setLong(1,IdempotencyService.lockId(scope,key));statement.execute();
      long start=System.nanoTime();
      try {
        var e=assertThrows(V2Exception.class,()->idempotency.execute(scope,key,"a".repeat(64),()->insert("blocked-"+key,null)));
        assertEquals("idempotency_in_progress",e.code());
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()<7);
        assertEquals(0,write.queryForObject("SELECT count(*) FROM dvp.idempotency_requests WHERE request_key=?",Integer.class,key));
      } finally {
        try(var unlock=connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          unlock.setLong(1,IdempotencyService.lockId(scope,key));unlock.execute();
        }
      }
    }
  }
  @Test void failedWriteRollsBackResponseAndMutationAndRetentionProtectsActiveJob() {
    String key=UUID.randomUUID().toString();
    assertThrows(IllegalStateException.class,()->idempotency.execute("POST /fail",key,"a".repeat(64),()->{
      insert("failed-"+key,null);throw new IllegalStateException("injected");
    }));
    assertEquals(0,write.queryForObject("SELECT count(*) FROM dvp.collections WHERE slug=?",Integer.class,"failed-"+key));
    assertEquals(0,write.queryForObject("SELECT count(*) FROM dvp.idempotency_requests WHERE request_key=?",Integer.class,key));
    UUID job=UUID.randomUUID();
    var result=idempotency.execute("POST /retention",key,"a".repeat(64),()->insert("active-"+key,job));
    write.update("UPDATE dvp.idempotency_requests SET created_at=now()-interval '8 days' WHERE request_key=?",key);
    idempotency.purgeExpired(100);
    assertEquals(result,idempotency.execute("POST /retention",key,"a".repeat(64),()->{fail();return null;}));
    write.update("UPDATE dvp.jobs SET status='succeeded' WHERE id=?",job);
    assertEquals(1,idempotency.purgeExpired(100));
  }
  @Test void saturatedReadsAndBlockedInferenceLeaveWritesAndCoordinationAvailable() throws Exception {
    var release=new CountDownLatch(1);var started=new CountDownLatch(1);
    try(var heldRead=reads.getConnection()) {
      var future=inferenceExecutor.submit(()->{started.countDown();release.await();return 1;});
      assertTrue(started.await(2,TimeUnit.SECONDS));
      try {
        assertThrows(RejectedExecutionException.class,()->inferenceExecutor.submit(()->1));
        assertTimeout(Duration.ofSeconds(2),()->{
          assertEquals(1,write.queryForObject("SELECT 1",Integer.class));
          assertEquals(1,coordination.queryForObject("SELECT 1",Integer.class));
        });
        assertThrows(java.sql.SQLException.class,()->reads.getConnection());
        assertEquals(1,reads.getMaximumPoolSize());assertEquals(0,inferenceExecutor.getQueue().size());
      } finally { release.countDown();future.get(2,TimeUnit.SECONDS); }
    }
    try(var releasedRead=reads.getConnection()) { assertTrue(releasedRead.isValid(1)); }
  }
  @Test void schemaHasAllRegistriesAndMissingExtensionIsRejected() throws Exception {
    for(String table:List.of("collections","documents","source_texts","embedding_profiles","indexations",
        "vector_units","jobs","job_items","import_files","idempotency_requests")) {
      assertNotNull(write.queryForObject("SELECT to_regclass(?)",String.class,"dvp."+table));
    }
    var missing=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(TestDatabase.createWithoutExtension(),"postgres",""));
    assertThrows(IllegalStateException.class,()->new VectorCapabilityCheck(missing).run(null));
  }
}
