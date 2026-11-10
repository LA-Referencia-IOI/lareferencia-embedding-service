package org.lareferencia.embedding.v2.indexation;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.job.JobHandlers;
import org.lareferencia.embedding.v2.job.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true"})
@AutoConfigureMockMvc
class IndexationIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
  }
  @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Autowired JobRepository jobs;@Autowired JobHandlers handlers;
  @org.junit.jupiter.api.AfterEach void releaseTestJobs() {
    jdbc.update("UPDATE dvp.jobs SET status='failed',lease_owner=NULL,lease_until=NULL WHERE status IN ('queued','running')");
  }
  private UUID collection() {UUID id=UUID.randomUUID();jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,'test')",id,"c-"+id);return id;}
  private UUID profile(int dimensions) {UUID id=UUID.randomUUID();jdbc.update("""
      INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint)
      VALUES (?,?,?, 'v1',?,'import_only','{}'::jsonb,?)
      """,id,"p-"+id,"model-"+id,dimensions,id.toString().replace("-","")+id.toString().replace("-",""));return id;}
  private String path(UUID c){return "/api/v2/collections/"+c+"/indexations";}
  private JsonNode create(UUID c,UUID p,String strategy,int status) throws Exception {
    String body="{\"slug\":\"test\",\"profile_id\":\""+p+"\",\"ann_strategy\":\""+strategy+"\"}";
    return mapper.readTree(mvc.perform(post(path(c)).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
  }
  private void run(UUID expectedJob) throws Exception {
    var lease=jobs.claim("test-"+UUID.randomUUID(),java.util.Set.of("maintenance")).orElseThrow();assertEquals(expectedJob,lease.jobId());
    handlers.find("maintenance").orElseThrow().run(lease,jobs);assertTrue(jobs.finishIfComplete(lease));
  }
  @Test void createsProvisioningJobAndBuildsRealAnnIndex() throws Exception {
    UUID c=collection(),p=profile(3);JsonNode created=create(c,p,"hnsw_vector",202);UUID i=UUID.fromString(created.path("id").asText());
    assertEquals("provisioning",created.path("state").asText());run(UUID.fromString(created.path("job_id").asText()));
    mvc.perform(get(path(c)+"/"+i)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ready")).andExpect(jsonPath("$.ann_state").value("pending"));
    String body="{\"operation\":\"build_ann\"}";var result=mvc.perform(post(path(c)+"/"+i+"/maintenance-jobs").header("Idempotency-Key","build").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn();
    run(UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("id").asText()));
    mvc.perform(get(path(c)+"/"+i)).andExpect(status().isOk()).andExpect(jsonPath("$.ann_state").value("ready"));
    assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname='dvp' AND indexname=?)",Boolean.class,IndexationService.table(i)+"_ann"));
  }
  @Test void halfvecBuildIsValidatedAndMarkedReady() throws Exception {
    UUID c=collection(),p=profile(3);JsonNode created=create(c,p,"hnsw_halfvec",202);UUID i=UUID.fromString(created.path("id").asText());
    run(UUID.fromString(created.path("job_id").asText()));
    var result=mvc.perform(post(path(c)+"/"+i+"/maintenance-jobs").header("Idempotency-Key","half-build").contentType(MediaType.APPLICATION_JSON).content("{\"operation\":\"build_ann\"}")).andExpect(status().isAccepted()).andReturn();
    run(UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("id").asText()));
    mvc.perform(get(path(c)+"/"+i)).andExpect(status().isOk()).andExpect(jsonPath("$.ann_state").value("ready"));
  }
  @Test void dimensionLimitsAndMissingTableAreDetected() throws Exception {
    UUID c=collection();create(c,profile(2001),"hnsw_vector",422);create(c,profile(4001),"hnsw_halfvec",422);
    JsonNode created=create(c,profile(16000),"none",202);UUID i=UUID.fromString(created.path("id").asText());
    // Simulate an uncommitted/absent table without dropping any data.
    UUID job=UUID.fromString(created.path("job_id").asText());jdbc.update("UPDATE dvp.jobs SET status='failed' WHERE id=?",job);
    var response=mvc.perform(post(path(c)+"/"+i+"/maintenance-jobs").header("Idempotency-Key","reconcile").contentType(MediaType.APPLICATION_JSON).content("{\"operation\":\"reconcile\"}")).andExpect(status().isAccepted()).andReturn();
    run(UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("id").asText()));
    mvc.perform(get(path(c)+"/"+i)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("failed"));
  }
  @Test void createAndMaintenanceAreIdempotent() throws Exception {
    UUID c=collection(),p=profile(3);String body="{\"slug\":\"replay\",\"profile_id\":\""+p+"\"}";String key=UUID.randomUUID().toString();
    var first=mvc.perform(post(path(c)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn();
    var replay=mvc.perform(post(path(c)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn();
    assertEquals(mapper.readTree(first.getResponse().getContentAsString()),mapper.readTree(replay.getResponse().getContentAsString()));
    assertEquals(first.getResponse().getHeader("Location"),replay.getResponse().getHeader("Location"));
    UUID id=UUID.fromString(mapper.readTree(first.getResponse().getContentAsString()).path("id").asText());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.jobs WHERE indexation_id=?",Integer.class,id));
    mvc.perform(post(path(c)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body.replace("replay","changed"))).andExpect(status().isConflict());
    mvc.perform(get(path(UUID.randomUUID())+"/"+id)).andExpect(status().isNotFound());
  }
  @Test void incompatibleExistingHalfvecPreventsAnnAndPreservesData() throws Exception {
    UUID c=collection();JsonNode created=create(c,profile(3),"hnsw_halfvec",202);UUID i=UUID.fromString(created.path("id").asText());
    run(UUID.fromString(created.path("job_id").asText()));UUID doc=UUID.randomUUID(),text=UUID.randomUUID(),unit=UUID.randomUUID();String hash="a".repeat(64);
    jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,'source')",doc,c);
    jdbc.update("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,content,content_hash) VALUES (?,?,'t','abstract','a',?)",text,doc,hash);
    jdbc.update("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash) VALUES (?,?,?,?,?,1,0,0,1,?)",unit,c,i,doc,text,hash);
    jdbc.update("INSERT INTO dvp."+IndexationService.table(i)+"(unit_id,document_id,source_text_id,source_revision,indexation_id,embedding,input_hash,origin) VALUES (?,?,?,1,?,'[0.000000001,0,0]'::vector,?,'imported')",unit,doc,text,i,hash);
    var response=mvc.perform(post(path(c)+"/"+i+"/maintenance-jobs").header("Idempotency-Key","bad-half").contentType(MediaType.APPLICATION_JSON).content("{\"operation\":\"build_ann\"}")).andExpect(status().isAccepted()).andReturn();
    UUID job=UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("id").asText());
    var lease=jobs.claim("test-"+UUID.randomUUID(),java.util.Set.of("maintenance")).orElseThrow();assertEquals(job,lease.jobId());
    assertThrows(IllegalStateException.class,()->handlers.find("maintenance").orElseThrow().run(lease,jobs));
    assertEquals("failed",jdbc.queryForObject("SELECT ann_state FROM dvp.indexations WHERE id=?",String.class,i));
    assertEquals("ready",jdbc.queryForObject("SELECT state FROM dvp.indexations WHERE id=?",String.class,i));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp."+IndexationService.table(i),Integer.class));
  }

  @Test void blockedAnnBuildKeepsHeartbeatAndIndependentWriteAvailable() throws Exception {
    UUID c=collection();JsonNode created=create(c,profile(3),"hnsw_vector",202);UUID i=UUID.fromString(created.path("id").asText());
    run(UUID.fromString(created.path("job_id").asText()));
    var response=mvc.perform(post(path(c)+"/"+i+"/maintenance-jobs").header("Idempotency-Key","blocked-build").contentType(MediaType.APPLICATION_JSON).content("{\"operation\":\"build_ann\"}")).andExpect(status().isAccepted()).andReturn();
    UUID job=UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("id").asText());
    var lease=jobs.claim("test-"+UUID.randomUUID(),java.util.Set.of("maintenance")).orElseThrow();assertEquals(job,lease.jobId());
    try(var lock=jdbc.getDataSource().getConnection();var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      lock.setAutoCommit(false);try(var statement=lock.createStatement()) { statement.execute("LOCK TABLE dvp."+IndexationService.table(i)+" IN ACCESS EXCLUSIVE MODE"); }
      var build=executor.submit(()->{handlers.find("maintenance").orElseThrow().run(lease,jobs);return true;});
      try {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
        while(System.nanoTime()<deadline && !waiting) {
          waiting=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid() AND (query LIKE 'CREATE INDEX CONCURRENTLY%' OR query LIKE '%pg_constraint fk%'))",Boolean.class);
          if(!waiting)Thread.sleep(50);
        }
        assertTrue(waiting,"ANN build did not block on the test lock");
        long start=System.nanoTime();assertTrue(jobs.heartbeat(lease));
        jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,'independent')",UUID.randomUUID(),"independent-"+UUID.randomUUID());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<3);
      } finally { lock.rollback(); }
      assertTrue(build.get(10,java.util.concurrent.TimeUnit.SECONDS));assertTrue(jobs.finishIfComplete(lease));
    }
  }

  @Test void pgvectorHalfPrecisionMatchesAdmissionBoundaries() {
    assertEquals("[65504,0]",jdbc.queryForObject("SELECT '[65505,0]'::vector::halfvec::text",String.class));
    assertEquals(0.0,jdbc.queryForObject("SELECT l2_norm('[0.000000001,0]'::vector::halfvec)",Double.class));
    assertTrue(jdbc.queryForObject("SELECT l2_norm('[0.00000006,0]'::vector::halfvec)>0",Boolean.class));
    assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.queryForObject("SELECT '[65520,0]'::vector::halfvec::text",String.class));
  }
  @Test void strategyDimensionBoundariesAreExplicit() throws Exception {
    UUID c=collection();create(c,profile(2000),"hnsw_vector",202);
    UUID second=collection();create(second,profile(2001),"hnsw_halfvec",202);
    UUID third=collection();create(third,profile(4000),"hnsw_halfvec",202);
    UUID fourth=collection();create(fourth,profile(4001),"none",202);
  }

  @Test void reconcileDetectsInvalidAnnIndexAndKeepsExactAccess() throws Exception {
    UUID c=collection();JsonNode created=create(c,profile(3),"hnsw_halfvec",202);UUID i=UUID.fromString(created.path("id").asText());
    run(UUID.fromString(created.path("job_id").asText()));UUID doc=UUID.randomUUID(),text=UUID.randomUUID(),unit=UUID.randomUUID();String hash="b".repeat(64),table=IndexationService.table(i);
    jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,'source')",doc,c);
    jdbc.update("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,content,content_hash) VALUES (?,?,'t','abstract','a',?)",text,doc,hash);
    jdbc.update("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash) VALUES (?,?,?,?,?,1,0,0,1,?)",unit,c,i,doc,text,hash);
    jdbc.update("INSERT INTO dvp."+table+"(unit_id,document_id,source_text_id,source_revision,indexation_id,embedding,input_hash,origin) VALUES (?,?,?,1,?,'[70000,0,0]'::vector,?,'imported')",unit,doc,text,i,hash);
    assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("CREATE INDEX CONCURRENTLY "+table+"_ann ON dvp."+table+" USING hnsw ((embedding::halfvec(3)) halfvec_cosine_ops)"));
    assertFalse(jdbc.queryForObject("SELECT indisvalid FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname=?",Boolean.class,table+"_ann"));
    var response=mvc.perform(post(path(c)+"/"+i+"/maintenance-jobs").header("Idempotency-Key","invalid-reconcile").contentType(MediaType.APPLICATION_JSON).content("{\"operation\":\"reconcile\"}")).andExpect(status().isAccepted()).andReturn();
    run(UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("id").asText()));
    mvc.perform(get(path(c)+"/"+i)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ready")).andExpect(jsonPath("$.ann_state").value("failed"));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp."+table,Integer.class));
  }

}
