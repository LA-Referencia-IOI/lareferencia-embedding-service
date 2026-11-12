package org.lareferencia.embedding.v2.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.role=API","app.dvp.workers-enabled=false","app.dvp.import-enabled=false","spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureMockMvc
@Import(JobRuntimeIntegrationTest.TestHandlers.class)
class JobRuntimeIntegrationTest {
  private static String databaseUrl;
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
    databaseUrl=TestDatabase.create();registry.add("spring.datasource.url",()->databaseUrl);
    registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"");
  }
  @TestConfiguration static class TestHandlers {
    @Bean JobHandler preparationHandler(ObjectMapper mapper) {
      return new JobHandler() {
        public String kind() { return "prepare"; }
        public JsonNode publicParameters(JsonNode internal) {
          var publicValue=mapper.createObjectNode();if(internal.has("label"))publicValue.set("label",internal.get("label"));
          return publicValue;
        }
        public void run(JobRepository.Lease lease,JobRepository jobs) {
          if(jobs.find(lease.jobId()).orElseThrow().parentJobId()==null) jobs.initializeEnumeration(lease);
          jobs.materialize(lease);
          Optional<JobRepository.ItemLease> item;
          while((item=jobs.claimItem(lease)).isPresent())
            jobs.complete(lease,item.get(),"succeeded",null,Map.of("documents_processed",1L),null,jdbc->{});
        }
      };
    }
  }
  @Autowired JobRepository jobs;
  @Autowired JobService service;
  @Autowired JobHandlers handlers;
  @Autowired DvpProperties properties;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Autowired @Qualifier("readDataSource") DataSource readDataSource;
  @Autowired @Qualifier("inferenceExecutor") ThreadPoolExecutor inferenceExecutor;

  private UUID collection() {
    UUID id=UUID.randomUUID();jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,'Test')",id,"test-"+id);
    return id;
  }
  private UUID document(UUID collection,String external) {
    UUID id=UUID.randomUUID();jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,?)",id,collection,external);
    return id;
  }
  private UUID job(UUID collection,String label) {
    var params=mapper.createObjectNode().put("label",label).put("secret", "NEVER-EXPOSE-DIAGNOSTIC");
    var result=service.create(collection,null,"prepare",params,UUID.randomUUID().toString(),
        "/api/v2/collections/"+collection+"/test-jobs",new LinkedMultiValueMap<>());
    return UUID.fromString(result.body().path("id").asText());
  }
  private JobRepository.Lease claim(UUID expected) {
    // Other tests may leave queued prepare jobs; target test jobs are leased directly for isolation.
    String owner=UUID.randomUUID().toString();
    return jdbc.queryForObject("""
        UPDATE dvp.jobs SET status='running',lease_owner=?,lease_until=clock_timestamp()+interval '2 minutes',
          fencing_token=fencing_token+1,attempts=attempts+1 WHERE id=?
          RETURNING id,lease_owner,fencing_token
        """,(rs,n)->new JobRepository.Lease(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3)),owner,expected);
  }

  @Test void listJobsPaginatesInUuidOrderAndExposesOnlyPublicFields() throws Exception {
    UUID c=collection(),first=job(c,"list-one"),second=job(c,"list-two");
    Set<String> found=new HashSet<>();String cursor=null,previous=null;
    do {
      var request=get("/api/v2/jobs").param("limit","1");
      if(cursor!=null) request.param("cursor",cursor);
      var response=mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      assertFalse(response.contains("NEVER-EXPOSE-DIAGNOSTIC"));assertFalse(response.contains("lease_owner"));
      var result=mapper.readTree(response);var items=result.path("items");assertTrue(items.size()<=1);
      if(!items.isEmpty()) {
        String id=items.get(0).path("id").asText();
        if(previous!=null) assertTrue(previous.compareTo(id)<0);
        assertTrue(found.add(id));previous=id;
      }
      cursor=result.path("next_cursor").isNull()?null:result.path("next_cursor").asText();
    } while(cursor!=null);
    assertTrue(found.contains(first.toString()));assertTrue(found.contains(second.toString()));
    mvc.perform(get("/api/v2/jobs").param("limit","0")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v2/jobs").param("limit","1001")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v2/jobs").param("cursor","invalid")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v2/jobs").param("limit","1","2")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v2/jobs").param("unknown","x")).andExpect(status().isBadRequest());
    var api=mapper.readTree(mvc.perform(get("/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertTrue(api.path("paths").path("/api/v2/jobs").has("get"));
  }

  @Test void idempotentCreationAndScopedJobApiExposeOnlySafeDiagnostics() throws Exception {
    UUID c=collection();var params=mapper.createObjectNode().put("label","public").put("secret","NEVER-EXPOSE-DIAGNOSTIC");
    String key=UUID.randomUUID().toString(),path="/api/v2/collections/"+c+"/test-jobs";
    var first=service.create(c,null,"prepare",params,key,path,new LinkedMultiValueMap<>());
    var replay=service.create(c,null,"prepare",params,key,path,new LinkedMultiValueMap<>());
    assertEquals(202,first.status());assertEquals(first.body(),replay.body());assertEquals(first.headers(),replay.headers());
    assertThrows(V2Exception.class,()->service.create(c,null,"prepare",mapper.createObjectNode(),key,path,new LinkedMultiValueMap<>()));
    assertThrows(V2Exception.class,()->service.create(c,null,"import",params,UUID.randomUUID().toString(),path,new LinkedMultiValueMap<>()));
    UUID id=first.jobId();
    mvc.perform(get("/api/v2/jobs/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.parameters.label").value("public"))
        .andExpect(jsonPath("$.parameters.secret").doesNotExist()).andExpect(jsonPath("$.lease_owner").doesNotExist());
    mvc.perform(get("/api/v2/jobs/"+UUID.randomUUID())).andExpect(status().isNotFound());
    mvc.perform(post("/api/v2/jobs/"+id+"/retries").header("Idempotency-Key",UUID.randomUUID().toString()))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("job_active"));
    jdbc.update("UPDATE dvp.jobs SET status='completed_with_errors',finished_at=now() WHERE id=?",id);
    jdbc.update("""
        INSERT INTO dvp.job_items(job_id,item_key,status,error_code,attempts,locator,error_details)
        VALUES (?,'a','failed','bad_vector',1,'{"line":1,"source_body":"NEVER-EXPOSE-DIAGNOSTIC"}',
        '{"message":"NEVER-EXPOSE-DIAGNOSTIC"}')
        """,id);
    jdbc.update("INSERT INTO dvp.job_items(job_id,item_key,status,error_code,attempts) VALUES (?,'b','failed','bad_vector',2)",id);
    var page=mvc.perform(get("/api/v2/jobs/"+id+"/errors").param("limit","1"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].code").value("bad_vector")).andReturn();
    String body=page.getResponse().getContentAsString();assertFalse(body.contains("NEVER-EXPOSE-DIAGNOSTIC"));
    String cursor=mapper.readTree(body).path("next_cursor").asText();
    mvc.perform(get("/api/v2/jobs/"+id+"/errors").param("limit","1").param("cursor",cursor))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].item_key").value("b"));
    String retryKey=UUID.randomUUID().toString();
    var retry=mvc.perform(post("/api/v2/jobs/"+id+"/retries").header("Idempotency-Key",retryKey))
        .andExpect(status().isAccepted()).andReturn();
    var repeated=mvc.perform(post("/api/v2/jobs/"+id+"/retries").header("Idempotency-Key",retryKey))
        .andExpect(status().isAccepted()).andReturn();
    assertEquals(mapper.readTree(retry.getResponse().getContentAsString()),
        mapper.readTree(repeated.getResponse().getContentAsString()));
    assertEquals(retry.getResponse().getHeader("Location"),repeated.getResponse().getHeader("Location"));
    UUID child=UUID.fromString(mapper.readTree(retry.getResponse().getContentAsString()).path("id").asText());
    var lease=claim(child);assertEquals(2,jobs.materialize(lease));
    assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM dvp.job_items WHERE job_id=?",Integer.class,child));
  }

  @Test void fencingPreventsStaleOwnerAndRollsBackDomainWritesAndCounters() {
    UUID c=collection();document(c,"one");UUID id=job(c,"fence");var first=claim(id);
    jobs.initializeEnumeration(first);assertEquals(1,jobs.materialize(first));var item=jobs.claimItem(first).orElseThrow();
    jdbc.execute("CREATE TABLE IF NOT EXISTS dvp.test_job_effects(id uuid primary key)");UUID effect=UUID.randomUUID();
    assertThrows(IllegalStateException.class,()->jobs.complete(first,item,"succeeded",null,Map.of("documents_processed",1L),null,
        tx->{tx.update("INSERT INTO dvp.test_job_effects(id) VALUES (?)",effect);throw new IllegalStateException("mock");}));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.test_job_effects WHERE id=?",Integer.class,effect));
    jdbc.update("UPDATE dvp.jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
    jdbc.update("UPDATE dvp.job_items SET lease_until=clock_timestamp()-interval '1 second' WHERE job_id=?",id);
    assertFalse(jobs.heartbeat(first));var second=claim(id);assertTrue(second.token()>first.token());
    var replacement=jobs.claimItem(second).orElseThrow();assertTrue(replacement.token()>item.token());
    assertThrows(JobRepository.LeaseLostException.class,()->jobs.complete(first,item,"succeeded",null,Map.of(),null,tx->{}));
    jobs.complete(second,replacement,"succeeded",null,Map.of("documents_processed",1L),null,
        tx->tx.update("INSERT INTO dvp.test_job_effects(id) VALUES (?)",effect));
    assertThrows(JobRepository.LeaseLostException.class,()->jobs.complete(second,replacement,"succeeded",null,Map.of(),null,tx->{}));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.test_job_effects WHERE id=?",Integer.class,effect));
    assertEquals(1,jobs.find(id).orElseThrow().counters().path("documents_processed").asInt());
    assertTrue(jobs.finishIfComplete(second));assertEquals("succeeded",jobs.find(id).orElseThrow().status());
  }

  @Test void enumerationIsBoundedAndExcludesLaterInserts() {
    UUID c=collection();jdbc.update("""
        INSERT INTO dvp.documents(id,collection_id,external_id)
        SELECT md5(?::text || ':' || n)::uuid,?,n::text FROM generate_series(1,1001) n
        """,c.toString(),c);
    UUID id=job(c,"bounded");var lease=claim(id);jobs.initializeEnumeration(lease);
    UUID late=document(c,"later");
    for(int i=0;i<10;i++)assertEquals(100,jobs.materialize(lease));
    assertEquals(0,jobs.materialize(lease));
    assertEquals(1000,jdbc.queryForObject("SELECT count(*) FROM dvp.job_items WHERE job_id=?",Integer.class,id));
    assertFalse(jobs.finishIfComplete(lease));
    var item=jobs.claimItem(lease).orElseThrow();jobs.complete(lease,item,"succeeded",null,Map.of(),null,tx->{});
    assertEquals(1,jobs.materialize(lease));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.job_items WHERE job_id=? AND document_id=?",Integer.class,id,late));
  }

  @Test void realSeparateProcessesCannotClaimTheSameJob() throws Exception {
    UUID c=collection(),id=UUID.randomUUID();
    jdbc.update("INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters) VALUES (?,?,'maintenance','queued','{}')",id,c);
    String java=System.getProperty("java.home")+"/bin/java";
    String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
    Process a=new ProcessBuilder(java,"-cp",classpath,StandaloneWorkerProbe.class.getName(),databaseUrl).redirectErrorStream(true).start();
    Process b=new ProcessBuilder(java,"-cp",classpath,StandaloneWorkerProbe.class.getName(),databaseUrl).redirectErrorStream(true).start();
    assertTrue(a.waitFor(20,TimeUnit.SECONDS));assertTrue(b.waitFor(20,TimeUnit.SECONDS));
    String left=new String(a.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
    String right=new String(b.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
    assertEquals(0,a.exitValue());assertEquals(0,b.exitValue());
    assertEquals(1,(left.contains("CLAIMED")?1:0)+(right.contains("CLAIMED")?1:0));
    assertEquals(1,jobs.find(id).orElseThrow().fencingToken());
  }

  @Test void retryUsesPersistedDueTimeAndTerminalGuard() {
    UUID c=collection();document(c,"retry");UUID id=job(c,"retry");var lease=claim(id);
    jobs.initializeEnumeration(lease);jobs.materialize(lease);var item=jobs.claimItem(lease).orElseThrow();
    jobs.retryOrFail(lease,item,"provider_timeout",2000);assertTrue(jobs.claimItem(lease).isEmpty());
    assertFalse(jobs.finishIfComplete(lease));assertEquals(1,jobs.find(id).orElseThrow().counters().path("retries").asInt());
    jdbc.update("UPDATE dvp.job_items SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE job_id=?",id);
    var retried=jobs.claimItem(lease).orElseThrow();assertEquals(2,retried.attempts());
    jobs.complete(lease,retried,"failed","provider_timeout",Map.of("documents_failed",1L),null,tx->{});
    assertTrue(jobs.finishIfComplete(lease));assertEquals("completed_with_errors",jobs.find(id).orElseThrow().status());
  }

  @Test void advisoryLockIsExclusiveAndLostSessionPreventsPublication() {
    UUID c=collection(),document=document(c,"locked"),profile=UUID.randomUUID(),index=UUID.randomUUID();
    jdbc.update("""
        INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint)
        VALUES (?,?,'model','v1',3,'import_only','{}',?)
        """,profile,"profile-"+profile,profile.toString().replace("-","").repeat(2));
    jdbc.update("""
        INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state)
        VALUES (?,?,'idx',?,'{}','{}','cosine','none',?,'ready','disabled')
        """,index,c,profile,"vec_"+index.toString().replace("-",""));
    UUID jobId=UUID.randomUUID();
    jdbc.update("INSERT INTO dvp.jobs(id,collection_id,indexation_id,kind,status,parameters) VALUES (?,?,?,'generate','queued','{}')",
        jobId,c,index);
    var lease=claim(jobId);jobs.initializeEnumeration(lease);jobs.materialize(lease);var item=jobs.claimItem(lease).orElseThrow();
    var lock=jobs.tryDocumentLock(index,document).orElseThrow();
    assertTrue(jobs.tryDocumentLock(index,document).isEmpty());
    lock.close();
    assertThrows(JobRepository.LeaseLostException.class,()->jobs.completeWithAdvisoryLock(lease,item,"succeeded",null,
        Map.of("documents_processed",1L),null,lock,tx->fail("Lost advisory session must not publish")));
    assertEquals("running",jdbc.queryForObject("SELECT status FROM dvp.job_items WHERE job_id=?",String.class,jobId));
    assertFalse(jobs.find(jobId).orElseThrow().counters().has("documents_processed"));
    try(var replacement=jobs.tryDocumentLock(index,document).orElseThrow()) {
      jobs.completeWithAdvisoryLock(lease,item,"succeeded",null,Map.of("documents_processed",1L),null,replacement,tx->{});
    }
    assertTrue(jobs.finishIfComplete(lease));
  }

  @Test void heartbeatAndWritesSurviveReadSaturationAndBlockedProvider() throws Exception {
    UUID c=collection(),id=job(c,"resource");var lease=claim(id);
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/provider",exchange->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.sendResponseHeaders(200,-1);exchange.close();});
    server.start();List<Connection> held=new ArrayList<>();
    try {
      var pending=inferenceExecutor.submit(()->HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/provider")).timeout(Duration.ofSeconds(8)).GET().build(),
          HttpResponse.BodyHandlers.discarding()));
      assertTrue(entered.await(3,TimeUnit.SECONDS));
      for(int i=0;i<properties.getReadConnections();i++)held.add(readDataSource.getConnection());
      try(var executor=Executors.newFixedThreadPool(2)) {
        var heartbeat=executor.submit(()->jobs.heartbeat(lease));
        var write=executor.submit(()->document(c,"write-while-busy"));
        assertTrue(heartbeat.get(3,TimeUnit.SECONDS));assertNotNull(write.get(3,TimeUnit.SECONDS));
      }
      release.countDown();assertEquals(200,pending.get(5,TimeUnit.SECONDS).statusCode());
    } finally { release.countDown();for(var connection:held)connection.close();server.stop(0); }
  }
}
