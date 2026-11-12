package org.lareferencia.embedding.v2.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.lareferencia.embedding.v2.profile.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true",
    "app.dvp.providers.first.type=OPENAI_COMPATIBLE","app.dvp.providers.first.endpoint=http://localhost:12345/v1/embeddings",
    "app.dvp.providers.second.type=OLLAMA","app.dvp.providers.second.endpoint=http://localhost:12346/api/embed",
    "app.dvp.providers.test-provider.type=OPENAI_COMPATIBLE","app.dvp.providers.test-provider.endpoint=http://localhost:12347/v1/embeddings"})
@AutoConfigureMockMvc
class ProfilesIntegrationTest {
  private static final String PATH="/api/v2/embedding-profiles";
  private static HttpServer slowServer;
  private static final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
    try {
      slowServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      slowServer.createContext("/slow",exchange->{entered.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();});
      slowServer.start();
    } catch(java.io.IOException e) { throw new IllegalStateException(e); }
    r.add("app.dvp.providers.slow.type",()->"OPENAI_COMPATIBLE");
    r.add("app.dvp.providers.slow.endpoint",()->"http://127.0.0.1:"+slowServer.getAddress().getPort()+"/slow");
    r.add("app.dvp.providers.slow.request-timeout",()->"2s");
  }
  @AfterAll static void stopServer() { release.countDown();if(slowServer!=null) slowServer.stop(0); }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Autowired @Qualifier("coordinationJdbc") JdbcTemplate coordination;
  @Autowired ProviderRegistry registry;
  private String request(String slug,String model,String mode,String provider,String preparation) {
    return "{\"slug\":\""+slug+"\",\"model\":\""+model+"\",\"model_revision\":\"deployment-v1\",\"dimensions\":3,\"execution_mode\":\""+mode+"\","+
        (provider==null?"":"\"provider_ref\":\""+provider+"\",")+"\"preparation\":"+preparation+"}";
  }
  private MvcResult create(String key,String body,int expected) throws Exception {
    return mvc.perform(post(PATH).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected)).andReturn();
  }
  private JsonNode json(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)); }
  @Test void createsProfilesWithoutInferenceAndReplaysIdempotently() throws Exception {
    String key=UUID.randomUUID().toString(),slug="http-"+UUID.randomUUID();
    String body=request(slug,"model-a","http","first","{\"version\":\"identity-v1\"}");
    var created=create(key,body,201);var value=json(created);
    assertTrue(value.path("inference_capable").asBoolean());assertEquals("",value.path("preparation").path("document_prefix").asText());
    assertEquals(value,json(create(key,body,201)));assertEquals(PATH+"/"+value.path("id").asText(),created.getResponse().getHeader("Location"));
    mvc.perform(get(created.getResponse().getHeader("Location"))).andExpect(status().isOk()).andExpect(jsonPath("$.provider_ref").value("first"));
    create(UUID.randomUUID().toString(),request("second-"+UUID.randomUUID(),"model-a","http","second","{\"version\":\"identity-v1\"}"),201);
    assertFalse(value.toString().contains("12345"));assertFalse(value.toString().contains("secret"));
    assertEquals(409,create(key,body.replace("model-a","model-b"),409).getResponse().getStatus());
  }
  @Test void normalizesDefaultsAndRejectsDuplicateFingerprintsAndSlugsWithReference() throws Exception {
    String slug="defaults-"+UUID.randomUUID(),model="m-"+UUID.randomUUID();
    var created=create(UUID.randomUUID().toString(),request(slug,model,"import_only",null,"{\"version\":\"identity-v1\"}"),201);
    assertFalse(json(created).path("inference_capable").asBoolean());
    var duplicate=create(UUID.randomUUID().toString(),request("another-"+UUID.randomUUID(),model,"import_only",null,
        "{\"version\":\"identity-v1\",\"document_prefix\":\"\",\"query_prefix\":\"\"}"),409);
    assertTrue(json(duplicate).path("message").asText().contains(json(created).path("id").asText()));
    create(UUID.randomUUID().toString(),request(slug,"different","import_only",null,"{\"version\":\"identity-v1\"}"),409);
    create(UUID.randomUUID().toString(),request("prefix-"+UUID.randomUUID(),model,"import_only",null,"{\"version\":\"identity-v1\",\"document_prefix\":\"passage: \"}"),201);
  }
  @Test void fingerprintsCanonicalRecipeWithNormalizedDefaults() throws Exception {
    var result=create(UUID.randomUUID().toString(),request("canonical-"+UUID.randomUUID(),"canonical-model","import_only",null,"{\"version\":\"identity-v1\"}"),201);
    assertEquals("c0913a4fe16cc4651cf5f661d313b0dde72e51e197e8ca6bbf105bd9daffac26",json(result).path("fingerprint").asText());
  }
  @Test void validationPaginationAndImmutability() throws Exception {
    assertTrue(registry.contains("test-provider"));
    create(UUID.randomUUID().toString(),request("missing-"+UUID.randomUUID(),"x","http","unknown","{\"version\":\"identity-v1\"}"),422);
    create(UUID.randomUUID().toString(),request("wrong-"+UUID.randomUUID(),"x","import_only","first","{\"version\":\"identity-v1\"}"),400);
    create(UUID.randomUUID().toString(),request("bad-"+UUID.randomUUID(),"x","http","first","{\"version\":\"unknown\"}"),400);
    create(UUID.randomUUID().toString(),request("float-"+UUID.randomUUID(),"x","http","first","{\"version\":\"identity-v1\"}").replace("\"dimensions\":3","\"dimensions\":3.5"),400);
    create(UUID.randomUUID().toString(),request("string-"+UUID.randomUUID(),"x","http","first","{\"version\":\"identity-v1\"}").replace("\"dimensions\":3","\"dimensions\":\"3\""),400);
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    mvc.perform(get(PATH+"/"+UUID.randomUUID())).andExpect(status().isNotFound());
    mvc.perform(patch(PATH+"/"+UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isMethodNotAllowed());
    var page=json(mvc.perform(get(PATH).param("limit","1")).andExpect(status().isOk()).andReturn());
    if(!page.path("next_cursor").isNull()) mvc.perform(get(PATH).param("limit","1").param("cursor",page.path("next_cursor").asText())).andExpect(status().isOk());
    mvc.perform(get(PATH).param("limit","0")).andExpect(status().isBadRequest());
  }
  @Test void concurrentSameFingerprintCreatesExactlyOneProfile() throws Exception {
    String model="concurrent-"+UUID.randomUUID();var barrier=new CyclicBarrier(2);
    try(var executor=Executors.newFixedThreadPool(2)) {
      var jobs=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()->{barrier.await(5,TimeUnit.SECONDS);
        return mvc.perform(post(PATH).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content(request("race-"+i+"-"+model,model,"import_only",null,"{\"version\":\"identity-v1\"}"))).andReturn().getResponse().getStatus();})).toList();
      var results=new ArrayList<Integer>();for(var job:jobs)results.add(job.get(10,TimeUnit.SECONDS));Collections.sort(results);assertEquals(List.of(201,409),results);
    }
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.embedding_profiles WHERE model=?",Integer.class,model));
  }
  @Test void openApiDocumentsProfileRoutesAndRequestSchema() throws Exception {
    JsonNode api=json(mvc.perform(get("/api-docs")).andExpect(status().isOk()).andReturn());
    assertTrue(api.path("paths").path(PATH).has("post"));
    assertTrue(api.path("paths").path(PATH).has("get"));
    assertTrue(api.path("components").path("schemas").path("ProfileRequest").path("properties").has("model_revision"));
  }
  @Test void blockedInferenceDoesNotOccupyDatabasePools() throws Exception {
    var p=new ProfileResource(UUID.randomUUID(),"slow","model","v1",2,"http","slow",
        new ProfileRequest.Preparation("identity-v1","",""),"0".repeat(64),true,java.time.Instant.now());
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      var call=executor.submit(()->assertThrows(org.lareferencia.embedding.v2.common.V2Exception.class,()->registry.embed(p,List.of("synthetic"))));
      try {
        assertTrue(entered.await(3,TimeUnit.SECONDS));
        assertEquals(1,jdbc.queryForObject("SELECT 1",Integer.class));
        assertEquals(1,coordination.queryForObject("SELECT 1",Integer.class));
      } finally {release.countDown();}
      call.get(5,TimeUnit.SECONDS);
    }
  }
}
