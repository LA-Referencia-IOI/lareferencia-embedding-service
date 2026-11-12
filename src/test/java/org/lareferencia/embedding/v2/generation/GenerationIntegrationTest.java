package org.lareferencia.embedding.v2.generation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.job.*;
import org.lareferencia.embedding.v2.profile.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.workers-enabled=false"})
@AutoConfigureMockMvc
class GenerationIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");}
  @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Autowired JobRepository jobs;@Autowired JobHandlers handlers;@MockitoBean ProviderRegistry provider;
  @org.junit.jupiter.api.AfterEach void releaseTestJobs() {
    jdbc.update("UPDATE dvp.jobs SET status='failed',lease_owner=NULL,lease_until=NULL WHERE status IN ('queued','running')");
  }
  record Fixture(UUID collection,UUID indexation,String table) { }
  private Fixture fixture(String mode,String strategy,String chunking) {
    UUID c=UUID.randomUUID(),p=UUID.randomUUID(),i=UUID.randomUUID();String table="vec_"+i.toString().replace("-","");
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",c,"c-"+c,"test");
    jdbc.update("""
        INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,provider_ref,preparation,fingerprint)
        VALUES (?,?,?,'v1',2,?,?, '{"version":"identity-v1","document_prefix":"passage: ","query_prefix":""}'::jsonb,?)
        """,p,"p-"+p,"model",mode,mode.equals("http")?"test":null,(p.toString().replace("-","")).repeat(2));
    jdbc.update("""
        INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state)
        VALUES (?,?,?,?,'{}'::jsonb,?::jsonb,'cosine',?,?,'ready','pending')
        """,i,c,"index",p,chunking,strategy,table);
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid NOT NULL,source_revision bigint NOT NULL,"
        +"indexation_id uuid NOT NULL CHECK(indexation_id='"+i+"'),embedding vector(2) NOT NULL,input_hash char(64) NOT NULL,"
        +"origin varchar(10) NOT NULL,origin_job_id uuid,created_at timestamptz NOT NULL DEFAULT now(),"
        +"FOREIGN KEY(unit_id,document_id,source_text_id,source_revision,indexation_id) REFERENCES dvp.vector_units(id,document_id,source_text_id,source_revision,indexation_id) ON DELETE CASCADE)");
    return new Fixture(c,i,table);
  }
  private UUID document(Fixture f,String external,String content) {
    UUID doc=UUID.randomUUID();jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,?)",doc,f.collection(),external);
    jdbc.update("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,content,content_hash) VALUES (?,?,'text','title',?,?)",UUID.randomUUID(),doc,content,"a".repeat(64));return doc;
  }
  private String route(Fixture f) {return "/api/v2/collections/"+f.collection()+"/indexations/"+f.indexation()+"/generation-jobs";}
  private UUID submit(Fixture f,String body,int expected) throws Exception {var r=mvc.perform(post(route(f)).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected)).andReturn();return expected==202?UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).path("id").asText()):null;}
  private void run(UUID job) throws Exception {var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("generate")).orElseThrow();assertEquals(job,lease.jobId());handlers.find("generate").orElseThrow().run(lease,jobs);assertTrue(jobs.finishIfComplete(lease));}
  @Test void generatesOnceAndReusesIdenticalCurrentContentWithinIndexation() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");UUID a=document(f,"a","same"),b=document(f,"b","same");
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenReturn(List.of(new float[]{1,2}));
    UUID job=submit(f,"{}",202);run(job);
    assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));verify(provider,times(1)).embed(any(),anyList());
    assertEquals(1,jobs.find(job).orElseThrow().counters().path("vectors_generated").asInt());
    assertEquals(1,jobs.find(job).orElseThrow().counters().path("vectors_reused").asInt());
    run(submit(f,"{}",202));verify(provider,times(1)).embed(any(),anyList());
    assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id IN (?,?)",Integer.class,a,b));
  }
  @Test void importOnlyCannotGenerateAndHalfvecOverflowFailsWithoutVector() throws Exception {
    Fixture imported=fixture("import_only","none","{\"mode\":\"none\"}");submit(imported,"{}",409);
    Fixture f=fixture("http","hnsw_halfvec","{\"mode\":\"none\"}");document(f,"a","body");
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenReturn(List.of(new float[]{70000,1}));
    UUID job=submit(f,"{}",202);run(job);
    assertEquals("completed_with_errors",jobs.find(job).orElseThrow().status());assertEquals("halfvec_not_representable",jobs.errors(job,null,10).getFirst().code());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
  }
  @Test void changedSourceDuringProviderCallNeverPublishesStaleVector() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");UUID doc=document(f,"race","before");
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenAnswer(inv->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();return List.of(new float[]{1,2});});
    UUID job=submit(f,"{}",202);var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("generate")).orElseThrow();
    try(var executor=Executors.newSingleThreadExecutor()) {
      var pending=executor.submit(()->{handlers.find("generate").orElseThrow().run(lease,jobs);return true;});
      try {
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        mvc.perform(patch("/api/v2/collections/"+f.collection()+"/documents/by-external-id").param("external_id","race")
            .contentType(MediaType.APPLICATION_JSON).content("{\"texts\":[{\"text_id\":\"text\",\"kind\":\"title\",\"content\":\"after\"}]}"))
            .andExpect(status().isOk());
      } finally {release.countDown();}
      assertTrue(pending.get(5,TimeUnit.SECONDS));assertTrue(jobs.finishIfComplete(lease));
    }
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
    assertEquals("skipped",jdbc.queryForObject("SELECT status FROM dvp.job_items WHERE job_id=? AND document_id=?",String.class,job,doc));
  }
  @Test void identicalTextsWithinOneDocumentAreDeduplicatedBeforeInference() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");UUID doc=document(f,"two-texts","same");
    jdbc.update("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,content,content_hash) VALUES (?,?,'second','title','same',?)",UUID.randomUUID(),doc,"b".repeat(64));
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenReturn(List.of(new float[]{1,2}));
    UUID job=submit(f,"{}",202);run(job);
    var inputs=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider,times(1)).embed(any(),inputs.capture());
    assertEquals(List.of("passage: same"),inputs.getValue());
    assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
  }
  @Test void retryableProviderFailureIsPersistedAndCanResume() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");document(f,"retry","body");
    when(provider.batchSize(any())).thenReturn(16);
    when(provider.embed(any(),anyList())).thenThrow(new org.lareferencia.embedding.v2.common.V2Exception(503,"provider_unavailable","Unavailable"))
        .thenReturn(List.of(new float[]{1,2}));
    UUID job=submit(f,"{}",202);var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("generate")).orElseThrow();
    handlers.find("generate").orElseThrow().run(lease,jobs);assertFalse(jobs.finishIfComplete(lease));
    assertEquals(1,jobs.find(job).orElseThrow().counters().path("retries").asInt());
    jdbc.update("UPDATE dvp.job_items SET next_attempt_at=clock_timestamp() WHERE job_id=?",job);
    handlers.find("generate").orElseThrow().run(lease,jobs);assertTrue(jobs.finishIfComplete(lease));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
    assertEquals(2,jdbc.queryForObject("SELECT attempts FROM dvp.job_items WHERE job_id=?",Integer.class,job));
  }
  @Test void staleJobLeaseCannotPublishLateProviderResults() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");document(f,"expired","body");
    when(provider.batchSize(any())).thenReturn(16);
    when(provider.embed(any(),anyList())).thenAnswer(inv->{jdbc.update("UPDATE dvp.jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE kind='generate' AND status='running'");return List.of(new float[]{1,2});});
    UUID job=submit(f,"{}",202);var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("generate")).orElseThrow();
    assertThrows(JobRepository.LeaseLostException.class,()->handlers.find("generate").orElseThrow().run(lease,jobs));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
  }
  @Test void overlappingJobsSerializeOnTheDocumentAndAvoidDuplicateInference() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");document(f,"overlap","body");
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenAnswer(inv->{entered.countDown();
      if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();return List.of(new float[]{1,2});});
    UUID first=submit(f,"{}",202),second=submit(f,"{}",202);
    var firstLease=jobs.claim("first-"+UUID.randomUUID(),Set.of("generate")).orElseThrow();assertEquals(first,firstLease.jobId());
    var secondLease=jobs.claim("second-"+UUID.randomUUID(),Set.of("generate")).orElseThrow();assertEquals(second,secondLease.jobId());
    try(var executor=Executors.newSingleThreadExecutor()) {
      var pending=executor.submit(()->{handlers.find("generate").orElseThrow().run(firstLease,jobs);return true;});
      try {
        assertTrue(entered.await(5,TimeUnit.SECONDS));handlers.find("generate").orElseThrow().run(secondLease,jobs);
        assertFalse(jobs.finishIfComplete(secondLease));assertEquals(1,jobs.find(second).orElseThrow().counters().path("retries").asInt());
      } finally {release.countDown();}
      assertTrue(pending.get(5,TimeUnit.SECONDS));assertTrue(jobs.finishIfComplete(firstLease));
    }
    jdbc.update("UPDATE dvp.job_items SET next_attempt_at=clock_timestamp() WHERE job_id=?",second);
    handlers.find("generate").orElseThrow().run(secondLease,jobs);assertTrue(jobs.finishIfComplete(secondLease));
    verify(provider,times(1)).embed(any(),anyList());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
  }
  @Test void generationCreationRequiresAndReplaysIdempotencyKeys() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");String key=UUID.randomUUID().toString();
    var first=mvc.perform(post(route(f)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isAccepted()).andReturn();
    var replay=mvc.perform(post(route(f)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isAccepted()).andReturn();
    assertEquals(mapper.readTree(first.getResponse().getContentAsString()),mapper.readTree(replay.getResponse().getContentAsString()));
    assertEquals(first.getResponse().getHeader("Location"),replay.getResponse().getHeader("Location"));
    mvc.perform(post(route(f)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"document_ids\":[]} "))
        .andExpect(status().isConflict());
    mvc.perform(post(route(f)).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
  }
  @Test void deletingDocumentDuringInferenceDiscardsTheProviderResult() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");UUID doc=document(f,"deleted","body");
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenAnswer(inv->{entered.countDown();
      if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();return List.of(new float[]{1,2});});
    UUID job=submit(f,"{}",202);var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("generate")).orElseThrow();
    try(var executor=Executors.newSingleThreadExecutor()) {
      var pending=executor.submit(()->{handlers.find("generate").orElseThrow().run(lease,jobs);return true;});
      try {assertTrue(entered.await(5,TimeUnit.SECONDS));mvc.perform(delete("/api/v2/collections/"+f.collection()+"/documents/"+doc)).andExpect(status().isNoContent());}
      finally {release.countDown();}
      assertTrue(pending.get(5,TimeUnit.SECONDS));assertTrue(jobs.finishIfComplete(lease));
    }
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
    assertEquals("skipped",jdbc.queryForObject("SELECT status FROM dvp.job_items WHERE job_id=?",String.class,job));
  }
  @Test void cosineZeroVectorIsARecordedPermanentFailure() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");document(f,"zero","body");
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenReturn(List.of(new float[]{0,0}));
    UUID job=submit(f,"{}",202);run(job);
    assertEquals("invalid_vector",jobs.errors(job,null,10).getFirst().code());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
  }
  @Test void reuseComparesPreparedContentEvenIfHashesCollide() throws Exception {
    Fixture f=fixture("http","none","{\"mode\":\"none\"}");UUID first=document(f,"collision-a","first"),second=document(f,"collision-b","second");
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenReturn(List.of(new float[]{1,2}));
    run(submit(f,"{\"document_ids\":[\""+first+"\"]}",202));
    String forged=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("passage: second".getBytes(StandardCharsets.UTF_8)));
    jdbc.update("UPDATE dvp.vector_units SET input_hash=? WHERE document_id=?",forged,first);
    jdbc.update("UPDATE dvp."+f.table()+" SET input_hash=? WHERE document_id=?",forged,first);
    run(submit(f,"{\"document_ids\":[\""+second+"\"]}",202));
    verify(provider,times(2)).embed(any(),anyList());assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
  }
  @Test void cosineHalfvecUnderflowIsRejected() throws Exception {
    Fixture f=fixture("http","hnsw_halfvec","{\"mode\":\"none\"}");document(f,"underflow","body");
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenReturn(List.of(new float[]{1e-9f,0}));
    UUID job=submit(f,"{}",202);run(job);
    assertEquals("halfvec_not_representable",jobs.errors(job,null,10).getFirst().code());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp."+f.table(),Integer.class));
  }
  @Test void identicalContentIsNeverReusedAcrossIndexConfigurations() throws Exception {
    Fixture first=fixture("http","none","{\"mode\":\"none\"}"),second=fixture("http","none","{\"mode\":\"none\"}");
    document(first,"one","same");document(second,"two","same");
    when(provider.batchSize(any())).thenReturn(16);when(provider.embed(any(),anyList())).thenReturn(List.of(new float[]{1,2}));
    run(submit(first,"{}",202));run(submit(second,"{}",202));
    verify(provider,times(2)).embed(any(),anyList());
  }
}
