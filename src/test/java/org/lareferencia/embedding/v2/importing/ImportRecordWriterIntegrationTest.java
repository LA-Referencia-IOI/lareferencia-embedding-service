package org.lareferencia.embedding.v2.importing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.workers-enabled=false","app.dvp.document-batch-size=1"})
class ImportRecordWriterIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");}
  @Autowired ImportRecordWriter writer;@Autowired ObjectMapper mapper;@Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Autowired JsonlImportProcessor processor;@Autowired JsonImportProcessor jsonProcessor;@Autowired org.lareferencia.embedding.v2.job.JobRepository jobs;
  @Autowired @Qualifier("writeTransactions") TransactionTemplate writes;
  @org.junit.jupiter.api.AfterEach void releaseTestJobs() {
    jdbc.update("UPDATE dvp.jobs SET status='failed',lease_owner=NULL,lease_until=NULL WHERE status IN ('queued','running')");
  }
  record Fixture(UUID collection,UUID profile,UUID indexation,UUID job,String table) { }
  private Fixture fixture(String strategy) {
    UUID c=UUID.randomUUID(),p=UUID.randomUUID(),i=UUID.randomUUID(),job=UUID.randomUUID();String table="vec_"+i.toString().replace("-","");
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",c,"c-"+c,"test");
    jdbc.update("INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint) VALUES (?,?,?,'v1',2,'import_only',?::jsonb,?)",p,"p-"+p,"model","{\"version\":\"identity-v1\",\"document_prefix\":\"passage: \",\"query_prefix\":\"\"}",p.toString().replace("-","").repeat(2));
    jdbc.update("INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state) VALUES (?,?,?,?,'{}'::jsonb,'{\"mode\":\"none\"}'::jsonb,'cosine',?,?,'ready','pending')",i,c,"index",p,strategy,table);
    jdbc.update("INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters) VALUES (?,?,'import','queued','{}'::jsonb)",job,c);
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,indexation_id uuid NOT NULL,embedding vector(2) NOT NULL,input_hash char(64) NOT NULL,origin varchar(10) NOT NULL,origin_job_id uuid,created_at timestamptz NOT NULL DEFAULT now(),FOREIGN KEY(unit_id,document_id,indexation_id) REFERENCES dvp.vector_units(id,document_id,indexation_id) ON DELETE CASCADE)");
    return new Fixture(c,p,i,job,table);
  }
  private JsonNode record(Fixture f,String content,String values,String conflict) throws Exception {
    return mapper.readTree("{\"external_id\":\"doi:one\",\"metadata\":{\"year\":2025},\"texts\":[{\"knd\":\"abstract\",\"cnt\":"+mapper.writeValueAsString(content)+"}]}");
  }
  private ImportRecordWriter.Result apply(Fixture f,JsonNode record,String mode) {return writes.execute(tx->writer.apply(f.collection(),f.job(),record,mode));}
  @Test void requiresTransactionAndHonorsExpectedRevision() throws Exception {
    Fixture f=fixture("none");JsonNode record=record(f,"body","[1,2]","keep");
    assertThrows(IllegalStateException.class,()->writer.apply(f.collection(),f.job(),record,"merge"));
    var expected=((com.fasterxml.jackson.databind.node.ObjectNode)record).put("expected_revision",1);
    assertEquals(412,assertThrows(V2Exception.class,()->apply(f,expected,"merge")).status());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?",Integer.class,f.collection()));
  }
  @Test void jsonlCheckpointRecoversAfterLeaseLossAndContinuesAfterMalformedLine() throws Exception {
    Fixture f=fixture("none");
    var first=(com.fasterxml.jackson.databind.node.ObjectNode)record(f,"first","[1,2]","keep");first.put("schema_version","1");
    var later=(com.fasterxml.jackson.databind.node.ObjectNode)record(f,"second","[3,4]","replace");later.put("schema_version","1");
    byte[] file=("\ufeff\n"+mapper.writeValueAsString(first)+"\n{bad json}\n"+mapper.writeValueAsString(later)+"\n\n").getBytes(StandardCharsets.UTF_8);
    jdbc.update("UPDATE dvp.jobs SET parameters='{\"format\":\"jsonl\",\"mode\":\"merge\"}'::jsonb WHERE id=?",f.job());
    var original=jobs.claim("original-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(f.job(),original.jobId());
    assertEquals(1,processor.process(original,new java.io.ByteArrayInputStream(file)));
    long checkpoint=jobs.find(f.job()).orElseThrow().checkpoint().path("offset").asLong();assertTrue(checkpoint>0);
    jdbc.update("UPDATE dvp.jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",f.job());
    var resumed=jobs.claim("resumed-"+UUID.randomUUID(),Set.of("import")).orElseThrow();
    assertThrows(org.lareferencia.embedding.v2.job.JobRepository.LeaseLostException.class,()->processor.process(original,new java.io.ByteArrayInputStream(file)));
    assertEquals(1,processor.process(resumed,new java.io.ByteArrayInputStream(file)));
    assertEquals(1,processor.process(resumed,new java.io.ByteArrayInputStream(file)));
    assertEquals(0,processor.process(resumed,new java.io.ByteArrayInputStream(file)));
    assertTrue(jobs.finishIfComplete(resumed));var done=jobs.find(f.job()).orElseThrow();
    assertEquals("completed_with_errors",done.status());assertEquals(file.length,done.checkpoint().path("offset").asLong());
    assertEquals(2,done.counters().path("documents_processed").asInt());assertEquals(1,done.counters().path("documents_failed").asInt());
    assertEquals("invalid_json",jobs.errors(f.job(),null,10).getFirst().code());
    assertEquals("second",jdbc.queryForObject("SELECT s.content FROM dvp.source_texts s JOIN dvp.documents d ON d.id=s.document_id WHERE d.collection_id=?",String.class,f.collection()));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?",Integer.class,f.collection()));
  }
  @Test void malformedGlobalJsonFailsAfterPreviouslyCommittedRecord() throws Exception {
    Fixture f=fixture("none");JsonNode first=record(f,"first","[1,2]","keep");
    byte[] file=("{\"schema_version\":\"1\",\"documents\":["+mapper.writeValueAsString(first)+",{\"broken\":}]}").getBytes(StandardCharsets.UTF_8);
    jdbc.update("UPDATE dvp.jobs SET parameters='{\"format\":\"json\",\"mode\":\"merge\"}'::jsonb WHERE id=?",f.job());
    var lease=jobs.claim("json-"+UUID.randomUUID(),Set.of("import")).orElseThrow();
    assertEquals(1,jsonProcessor.process(lease,()->new java.io.ByteArrayInputStream(file)));
    assertEquals(1,jobs.find(f.job()).orElseThrow().checkpoint().path("ordinal").asInt());
    assertEquals(0,jsonProcessor.process(lease,()->new java.io.ByteArrayInputStream(file)));
    assertEquals("failed",jobs.find(f.job()).orElseThrow().status());assertEquals("file_correction_required",jobs.find(f.job()).orElseThrow().errorCode());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?",Integer.class,f.collection()));
  }
  @Test void schemaAfterDocumentsIsPreflightedAndApplied() throws Exception {
    Fixture f=fixture("none");JsonNode first=record(f,"body","[1,2]","keep");
    byte[] file=("{\"documents\":["+mapper.writeValueAsString(first)+"],\"schema_version\":\"1\"}").getBytes(StandardCharsets.UTF_8);
    jdbc.update("UPDATE dvp.jobs SET parameters='{\"format\":\"json\",\"mode\":\"merge\"}'::jsonb WHERE id=?",f.job());
    var lease=jobs.claim("json-"+UUID.randomUUID(),Set.of("import")).orElseThrow();
    assertEquals(1,jsonProcessor.process(lease,()->new java.io.ByteArrayInputStream(file)));
    assertEquals(0,jsonProcessor.process(lease,()->new java.io.ByteArrayInputStream(file)));assertTrue(jobs.finishIfComplete(lease));
    assertEquals("succeeded",jobs.find(f.job()).orElseThrow().status());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?",Integer.class,f.collection()));
  }
  @Test void blockedRecordKeepsHeartbeatAndIndependentWritesAvailable() throws Exception {
    Fixture f=fixture("none");var lease=jobs.claim("blocked-"+UUID.randomUUID(),Set.of("import")).orElseThrow();
    UUID doc=UUID.randomUUID();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var pending=executor.submit(()->{jobs.commitImportRecord(lease,0,1,1,mapper.createObjectNode().put("line",1),null,tx->{
        tx.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,'blocked')",doc,f.collection());entered.countDown();
        try {if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        return new org.lareferencia.embedding.v2.job.JobRepository.ImportResult(doc,0);
      });return true;});
      try {
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));long start=System.nanoTime();assertTrue(jobs.heartbeat(lease));
        jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,'independent')",UUID.randomUUID(),"independent-"+UUID.randomUUID());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<3);
      } finally {release.countDown();}
      assertTrue(pending.get(5,java.util.concurrent.TimeUnit.SECONDS));
    }
  }
  @Test void staleImportOwnerRollsBackDocumentAndCheckpoint() throws Exception {
    Fixture f=fixture("none");var old=jobs.claim("old-"+UUID.randomUUID(),Set.of("import")).orElseThrow();UUID doc=UUID.randomUUID();
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var pending=executor.submit(()->assertThrows(org.lareferencia.embedding.v2.job.JobRepository.LeaseLostException.class,()->
          jobs.commitImportRecord(old,0,1,1,mapper.createObjectNode().put("line",1),null,tx->{
            tx.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,'stale')",doc,f.collection());entered.countDown();
            try {if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            return new org.lareferencia.embedding.v2.job.JobRepository.ImportResult(doc,0);
          })));
      try {
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
        jdbc.update("UPDATE dvp.jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",f.job());
        var replacement=jobs.claim("new-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(f.job(),replacement.jobId());
      } finally {release.countDown();}
      assertNotNull(pending.get(5,java.util.concurrent.TimeUnit.SECONDS));
    }
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE id=?",Integer.class,doc));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.job_items WHERE job_id=?",Integer.class,f.job()));
  }
}
