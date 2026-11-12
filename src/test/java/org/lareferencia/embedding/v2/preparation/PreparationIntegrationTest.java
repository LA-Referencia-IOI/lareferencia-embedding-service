package org.lareferencia.embedding.v2.preparation;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.job.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.workers-enabled=false"})
@AutoConfigureMockMvc
class PreparationIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
  }
  @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Autowired JobRepository jobs;@Autowired JobHandlers handlers;
  @MockitoBean org.lareferencia.embedding.v2.profile.ProviderRegistry providers;
  @org.junit.jupiter.api.AfterEach void noInference() {
    org.mockito.Mockito.verify(providers,org.mockito.Mockito.never()).embed(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
  }
  record Fixture(UUID collection,UUID indexation) { }
  private Fixture fixture(String selection,String chunking) {
    UUID c=UUID.randomUUID(),p=UUID.randomUUID(),i=UUID.randomUUID();
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",c,"c-"+c,"test");
    jdbc.update("""
        INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint)
        VALUES (?,?,?,'v1',3,'import_only','{"version":"identity-v1","document_prefix":"passage: ","query_prefix":""}'::jsonb,?)
        """,p,"p-"+p,"test-model",(p.toString().replace("-","")).repeat(2));
    jdbc.update("""
        INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state)
        VALUES (?,?,?, ?,?::jsonb,?::jsonb,'cosine','none',?,'ready','disabled')
        """,i,c,"index",p,selection,chunking,"vec_"+i.toString().replace("-",""));
    return new Fixture(c,i);
  }
  private UUID document(Fixture f,String name,String kind,String language,String content) {
    UUID doc=UUID.randomUUID(),text=UUID.randomUUID();
    jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,?)",doc,f.collection(),name);
    jdbc.update("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,language,content,content_hash) VALUES (?,?,'text',?,?,?,?)",
        text,doc,kind,language,content,"a".repeat(64));return doc;
  }
  private String route(Fixture f) {return "/api/v2/collections/"+f.collection()+"/indexations/"+f.indexation()+"/preparation-jobs";}
  private MvcResult submit(Fixture f,String key,String body,int expected) throws Exception {
    return mvc.perform(post(route(f)).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected)).andReturn();
  }
  private UUID id(MvcResult response) throws Exception {return UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("id").asText());}
  private void run(UUID id) throws Exception {
    var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("prepare")).orElseThrow();assertEquals(id,lease.jobId());
    handlers.find("prepare").orElseThrow().run(lease,jobs);assertTrue(jobs.finishIfComplete(lease));
  }
  @Test void preparesSelectedDocumentsWithExactOffsetsAndIsIdempotent() throws Exception {
    Fixture f=fixture("{\"kinds\":[\"abstract\"],\"languages\":[\"en\"]}","{\"mode\":\"characters-v1\",\"max_chars\":4,\"overlap_chars\":1}");
    UUID selected=document(f,"selected","abstract","en","🧬abcdef");
    UUID excluded=document(f,"excluded","abstract",null,"Excluded");
    String body="{\"document_ids\":[\""+selected.toString().toUpperCase(Locale.ROOT)+"\"]}",key=UUID.randomUUID().toString();
    var first=submit(f,key,body,202);assertEquals(id(first),id(submit(f,key,body,202)));run(id(first));
    assertEquals(2,jobs.find(id(first)).orElseThrow().counters().path("units_prepared").asInt());
    var units=jdbc.queryForList("SELECT ordinal,start_cp,end_cp,input_hash FROM dvp.vector_units WHERE document_id=? ORDER BY ordinal",selected);
    assertEquals(2,units.size());assertEquals(0,units.get(0).get("start_cp"));assertEquals(4,units.get(0).get("end_cp"));
    assertEquals(3,units.get(1).get("start_cp"));assertEquals(7,units.get(1).get("end_cp"));
    assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("passage: 🧬abc".getBytes(java.nio.charset.StandardCharsets.UTF_8))),units.get(0).get("input_hash"));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=?",Integer.class,excluded));
    UUID again=id(submit(f,UUID.randomUUID().toString(),"{}",202));run(again);
    assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=?",Integer.class,selected));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=?",Integer.class,excluded));
    assertEquals("succeeded",jobs.find(again).orElseThrow().status());
    assertEquals(0,jobs.find(again).orElseThrow().counters().path("units_prepared").asInt());
  }
  @Test void enforcesOwnershipValidationAndReadyState() throws Exception {
    Fixture f=fixture("{}","{\"mode\":\"none\"}"),other=fixture("{}","{\"mode\":\"none\"}");
    UUID foreign=document(other,"foreign","title",null,"body");
    submit(f,UUID.randomUUID().toString(),"{\"document_ids\":[\""+foreign+"\"]}",404);
    submit(f,UUID.randomUUID().toString(),"{\"document_ids\":[\"bad\"]}",400);
    submit(f,UUID.randomUUID().toString(),"{\"unknown\":true}",400);
    submit(f,UUID.randomUUID().toString(),mapper.writeValueAsString(Map.of("document_ids",Collections.nCopies(1001,foreign.toString()))),400);
    jdbc.update("UPDATE dvp.indexations SET state='failed' WHERE id=?",f.indexation());
    submit(f,UUID.randomUUID().toString(),"{}",409);
  }
  @Test void sourceChangeInvalidatesOldUnitsAndPreparationUsesNewRevision() throws Exception {
    Fixture f=fixture("{}","{\"mode\":\"none\"}");UUID doc=document(f,"revision","title",null,"before");
    run(id(submit(f,UUID.randomUUID().toString(),"{}",202)));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=? AND source_revision=1",Integer.class,doc));
    mvc.perform(patch("/api/v2/collections/"+f.collection()+"/documents/by-external-id").param("external_id","revision")
        .contentType(MediaType.APPLICATION_JSON).content("{\"texts\":[{\"text_id\":\"text\",\"kind\":\"title\",\"content\":\"after\"}]}"))
        .andExpect(status().isOk());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=?",Integer.class,doc));
    run(id(submit(f,UUID.randomUUID().toString(),"{}",202)));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=? AND source_revision=2",Integer.class,doc));
  }
  @Test void deletedDocumentAfterEnumerationIsSkippedSafely() throws Exception {
    Fixture f=fixture("{}","{\"mode\":\"none\"}");UUID doc=document(f,"gone","title",null,"body");
    UUID job=id(submit(f,UUID.randomUUID().toString(),"{}",202));
    var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("prepare")).orElseThrow();assertEquals(job,lease.jobId());
    jobs.initializeEnumeration(lease);assertEquals(1,jobs.materialize(lease));
    mvc.perform(delete("/api/v2/collections/"+f.collection()+"/documents/"+doc)).andExpect(status().isNoContent());
    handlers.find("prepare").orElseThrow().run(lease,jobs);assertTrue(jobs.finishIfComplete(lease));
    assertEquals("skipped",jdbc.queryForObject("SELECT status FROM dvp.job_items WHERE job_id=?",String.class,job));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=?",Integer.class,doc));
  }
  @Test void documentLockMakesPreparationObserveTheCommittedSourceRevision() throws Exception {
    Fixture f=fixture("{}","{\"mode\":\"none\"}");UUID doc=document(f,"locked","title",null,"before");
    UUID job=id(submit(f,UUID.randomUUID().toString(),"{}",202));
    var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("prepare")).orElseThrow();assertEquals(job,lease.jobId());
    jobs.initializeEnumeration(lease);assertEquals(1,jobs.materialize(lease));
    try(var connection=jdbc.getDataSource().getConnection();var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      try(var lock=connection.prepareStatement("SELECT id FROM dvp.documents WHERE id=? FOR UPDATE")){lock.setObject(1,doc);lock.execute();}
      try(var update=connection.prepareStatement("UPDATE dvp.source_texts SET content='after',revision=revision+1 WHERE document_id=?")){update.setObject(1,doc);update.executeUpdate();}
      var pending=executor.submit(()->{handlers.find("prepare").orElseThrow().run(lease,jobs);return true;});
      try {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%dvp.documents%FOR UPDATE%')",Boolean.class));
        assertThrows(java.util.concurrent.TimeoutException.class,()->pending.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
      } finally {connection.commit();}
      assertTrue(pending.get(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(jobs.finishIfComplete(lease));
    }
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=? AND source_revision=2",Integer.class,doc));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=? AND source_revision=1",Integer.class,doc));
  }
  @Test void enumerationUsesTheCapturedCollectionHighWaterMark() throws Exception {
    Fixture f=fixture("{}","{\"mode\":\"none\"}");UUID first=document(f,"first","title",null,"one");
    UUID job=id(submit(f,UUID.randomUUID().toString(),"{}",202));
    var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("prepare")).orElseThrow();assertEquals(job,lease.jobId());
    jobs.initializeEnumeration(lease);UUID later=document(f,"later","title",null,"two");
    handlers.find("prepare").orElseThrow().run(lease,jobs);assertTrue(jobs.finishIfComplete(lease));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=?",Integer.class,first));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=?",Integer.class,later));
  }
  @Test void expiredLeaseCannotPublishAndAReplacementWorkerResumes() throws Exception {
    Fixture f=fixture("{}","{\"mode\":\"none\"}");UUID doc=document(f,"recover","title",null,"body");
    UUID job=id(submit(f,UUID.randomUUID().toString(),"{}",202));
    var old=jobs.claim("old-"+UUID.randomUUID(),Set.of("prepare")).orElseThrow();
    jobs.initializeEnumeration(old);assertEquals(1,jobs.materialize(old));
    jdbc.update("UPDATE dvp.jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",job);
    var replacement=jobs.claim("new-"+UUID.randomUUID(),Set.of("prepare")).orElseThrow();assertEquals(job,replacement.jobId());
    assertThrows(JobRepository.LeaseLostException.class,()->handlers.find("prepare").orElseThrow().run(old,jobs));
    handlers.find("prepare").orElseThrow().run(replacement,jobs);assertTrue(jobs.finishIfComplete(replacement));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE document_id=?",Integer.class,doc));
  }
}
