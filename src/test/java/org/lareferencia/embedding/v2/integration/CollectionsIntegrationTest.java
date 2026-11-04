package org.lareferencia.embedding.v2.integration;

import org.lareferencia.embedding.integration.TestDatabase;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.http.MediaType;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.max-request-bytes=1024"})
@AutoConfigureMockMvc
class CollectionsIntegrationTest {
  private static final String PATH="/api/v2/collections";
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url=TestDatabase.create();r.add("spring.datasource.url",()->url);
    r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
  }
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  private String body(String slug,String name) throws Exception { return mapper.writeValueAsString(Map.of("slug",slug,"name",name)); }
  private MvcResult create(String key,String body,int status) throws Exception {
    return mvc.perform(post(PATH).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(status)).andReturn();
  }
  private JsonNode json(MvcResult response) throws Exception {
    return mapper.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
  @Test void unicodeDisplayNamesNeedNotBeUniqueAndReplayPreservesResponseAndLocation() throws Exception {
    String slug="c-"+UUID.randomUUID(),key=UUID.randomUUID().toString();
    String name="Investigación Abierta 🧬";
    var first=create(key,body(slug,name),201);
    var response=json(first);
    assertEquals(name,response.path("name").asText());assertTrue(response.hasNonNull("created_at"));
    assertFalse(response.has("createdAt"));
    var replay=create(key,body(slug,name),201);
    assertEquals(response,json(replay));
    assertEquals(first.getResponse().getHeader("Location"),replay.getResponse().getHeader("Location"));
    mvc.perform(get(first.getResponse().getHeader("Location"))).andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name));
    create(UUID.randomUUID().toString(),body("other-"+UUID.randomUUID(),name),201);
    assertEquals("collection_exists",json(create(UUID.randomUUID().toString(),body(slug,"Other"),409)).path("code").asText());
    assertEquals("idempotency_conflict",json(create(key,body(slug,"Other"),409)).path("code").asText());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.collections WHERE slug=?",Integer.class,slug));
  }
  @Test void paginationReturnsEachCollectionOnceAndRejectsWrongRouteCursor() throws Exception {
    create(UUID.randomUUID().toString(),body("page-"+UUID.randomUUID(),"Page"),201);
    create(UUID.randomUUID().toString(),body("page-"+UUID.randomUUID(),"Page"),201);
    Set<String> ids=new HashSet<>();String next=null;
    do {
      var request=get(PATH).param("limit","1"); if(next!=null) request.param("cursor",next);
      var result=json(mvc.perform(request).andExpect(status().isOk()).andReturn());
      for(var item:result.path("items")) assertTrue(ids.add(item.path("id").asText()));
      next=result.path("next_cursor").isNull()?null:result.path("next_cursor").asText();
    } while(next!=null);
    assertEquals(jdbc.queryForObject("SELECT count(*) FROM dvp.collections",Integer.class),ids.size());
    var codec=new org.lareferencia.embedding.v2.common.CursorCodec(mapper);
    String wrong=codec.encode("/api/v2/embedding-profiles","",List.of(UUID.randomUUID().toString()));
    mvc.perform(get(PATH).param("cursor",wrong)).andExpect(status().isBadRequest());
    for(String limit:List.of("0","1001","oops")) mvc.perform(get(PATH).param("limit",limit)).andExpect(status().isBadRequest());
    mvc.perform(get(PATH).param("limit","1","2")).andExpect(status().isBadRequest());
    mvc.perform(get(PATH).param("include","metadata")).andExpect(status().isBadRequest());
  }
  @Test void strictJsonErrorsLimitsAndMissingResourcesUseV2Shape() throws Exception {
    for(String content:List.of("{}","{\"slug\":\"UPPER\",\"name\":\"Name\"}",
        "{\"slug\":123,\"name\":456}",
        "{\"slug\":\"valid\",\"name\":\"Name\",\"unknown\":true}",
        "{\"slug\":\"one\",\"slug\":\"two\",\"name\":\"Name\"}",
        "{\"slug\":\"one\",\"name\":\"Name\"} {}")) {
      var error=create(UUID.randomUUID().toString(),content,400);
      assertTrue(json(error).hasNonNull("request_id"));assertTrue(json(error).has("code"));
      assertFalse(json(error).has("path"));
      assertEquals(json(error).path("request_id").asText(),error.getResponse().getHeader("X-Request-Id"));
    }
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("valid","Name")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
    mvc.perform(get(PATH+"/"+UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("collection_not_found"));
    mvc.perform(get(PATH+"/bad")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v2/unknown/route")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
    create(UUID.randomUUID().toString()," ".repeat(1025),413);
    mvc.perform(post("/api/v1/stores").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.path").value("/api/v1/stores"))
        .andExpect(jsonPath("$.request_id").doesNotExist());
  }
  @Test void nameLimitCountsCodePointsRatherThanUtf16Units() throws Exception {
    // UTF-8 bytes remain below this test's 1024-byte document limit.
    create(UUID.randomUUID().toString(),body("unicode-"+UUID.randomUUID(),"🧬".repeat(200)),201);
    create(UUID.randomUUID().toString(),body("unicode-"+UUID.randomUUID(),"🧬".repeat(201)),400);
  }
  @Test void concurrentSlugAndIdempotencyConflictsAreAtomicOverHttp() throws Exception {
    var barrier=new CyclicBarrier(2);
    String slug="race-"+UUID.randomUUID();
    try(var executor=Executors.newFixedThreadPool(2)) {
      var futures=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()->{
        barrier.await(3,TimeUnit.SECONDS);
        return mvc.perform(post(PATH).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body(slug,"Name"))).andReturn().getResponse().getStatus();
      })).toList();
      var codes=new ArrayList<Integer>();for(var f:futures) codes.add(f.get(5,TimeUnit.SECONDS));
      Collections.sort(codes);assertEquals(List.of(201,409),codes);
    }
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.collections WHERE slug=?",Integer.class,slug));
    String key=UUID.randomUUID().toString(),content=body("key-"+UUID.randomUUID(),"Name");
    try(var executor=Executors.newFixedThreadPool(2)) {
      var a=executor.submit(()->json(create(key,content,201)));
      var b=executor.submit(()->json(create(key,content,201)));
      assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
    }
  }
  @Test void openApiDescribesCollectionsAndIdempotency() throws Exception {
    var schema=json(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
    var post=schema.path("paths").path(PATH).path("post");
    assertFalse(post.isMissingNode());
    assertTrue(post.path("parameters").toString().contains("Idempotency-Key"));
    assertTrue(post.path("responses").has("201"));
    assertTrue(schema.path("components").path("schemas").path("CollectionResource").path("properties").has("created_at"));
  }
}
