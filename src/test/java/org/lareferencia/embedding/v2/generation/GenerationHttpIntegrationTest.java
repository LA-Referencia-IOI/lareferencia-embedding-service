package org.lareferencia.embedding.v2.generation;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.job.*;
import org.lareferencia.embedding.v2.profile.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.workers-enabled=false","app.dvp.inference-concurrency=1"})
class GenerationHttpIntegrationTest {
  private static HttpServer server;private static final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  @DynamicPropertySource static void setup(DynamicPropertyRegistry r) {
    String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
    try {
      server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/embed",exchange->{exchange.getRequestBody().readAllBytes();entered.countDown();
        try {if(!release.await(10,TimeUnit.SECONDS))throw new java.io.IOException("timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
        byte[] response="{\"data\":[{\"index\":0,\"embedding\":[1,2]}]}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});
      server.start();
    } catch(java.io.IOException e) {throw new IllegalStateException(e);}
    r.add("app.dvp.providers.test.type",()->"OPENAI_COMPATIBLE");
    r.add("app.dvp.providers.test.endpoint",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/embed");
    r.add("app.dvp.providers.test.concurrency",()->"1");r.add("app.dvp.providers.test.request-timeout",()->"15s");
  }
  @AfterAll static void stop() {release.countDown();if(server!=null)server.stop(0);}
  @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;@Autowired ObjectMapper mapper;
  @Autowired JobRepository jobs;@Autowired JobHandlers handlers;@Autowired ProviderRegistry provider;
  @Test void blockedHttpKeepsDatabaseAndHeartbeatsAvailableWhileEnforcingCapacity() throws Exception {
    UUID c=UUID.randomUUID(),p=UUID.randomUUID(),i=UUID.randomUUID(),d=UUID.randomUUID(),job=UUID.randomUUID();String table="vec_"+i.toString().replace("-","");
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",c,"c-"+c,"test");
    jdbc.update("INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,provider_ref,preparation,fingerprint) VALUES (?,?,?,'v1',2,'http','test',?::jsonb,?)",p,"p-"+p,"model","{\"version\":\"identity-v1\",\"document_prefix\":\"\",\"query_prefix\":\"\"}",p.toString().replace("-","").repeat(2));
    jdbc.update("INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state) VALUES (?,?,?,?,'{}'::jsonb,'{\"mode\":\"none\"}'::jsonb,'cosine','none',?,'ready','disabled')",i,c,"index",p,table);
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid NOT NULL,source_revision bigint NOT NULL,indexation_id uuid NOT NULL,embedding vector(2) NOT NULL,input_hash char(64) NOT NULL,origin varchar(10) NOT NULL,origin_job_id uuid,FOREIGN KEY(unit_id,document_id,source_text_id,source_revision,indexation_id) REFERENCES dvp.vector_units(id,document_id,source_text_id,source_revision,indexation_id) ON DELETE CASCADE)");
    jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,'doc')",d,c);
    jdbc.update("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,content,content_hash) VALUES (?,?,'text','title','synthetic',?)",UUID.randomUUID(),d,"a".repeat(64));
    jobs.insert(job,c,i,null,"generate",mapper.createObjectNode());var lease=jobs.claim("test-"+UUID.randomUUID(),Set.of("generate")).orElseThrow();
    var profile=new ProfileResource(p,"profile","model","v1",2,"http","test",new ProfileRequest.Preparation("identity-v1","",""),"a".repeat(64),true,Instant.now());
    try(var executor=Executors.newSingleThreadExecutor()) {
      var pending=executor.submit(()->{handlers.find("generate").orElseThrow().run(lease,jobs);return true;});
      try {
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        assertEquals("inference_busy",assertThrows(V2Exception.class,()->provider.embed(profile,List.of("synthetic"))).code());
        long start=System.nanoTime();assertTrue(jobs.heartbeat(lease));
        jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",UUID.randomUUID(),"independent-"+UUID.randomUUID(),"independent");
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<3);
      } finally {release.countDown();}
      assertTrue(pending.get(10,TimeUnit.SECONDS));assertTrue(jobs.finishIfComplete(lease));
    }
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp."+table,Integer.class));
    try(var available=jobs.tryDocumentLock(i,d).orElseThrow()) {available.requireAvailable();}
    assertArrayEquals(new float[]{1,2},provider.embed(profile,List.of("synthetic-again")).getFirst());
  }
}
