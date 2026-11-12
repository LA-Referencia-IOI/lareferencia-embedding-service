package org.lareferencia.embedding.v2.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.lareferencia.embedding.v1.service.EmbeddingProviderService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true",
    "app.dvp.max-request-bytes=8192","app.dvp.max-document-bytes=1024","app.dvp.max-metadata-bytes=256",
    "app.dvp.max-texts=3","app.dvp.max-text-code-points=128"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class DocumentsIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url=TestDatabase.create(); r.add("spring.datasource.url",()->url);
    r.add("spring.datasource.username",()->"postgres"); r.add("spring.datasource.password",()->"");
  }
  @Autowired MockMvc mvc;
  @MockitoBean EmbeddingProviderService provider;
  @org.junit.jupiter.api.AfterEach void noInference() {
    org.mockito.Mockito.verify(provider,org.mockito.Mockito.never()).embed(
        org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt());
  }
  @Autowired ObjectMapper mapper;
  @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Autowired @Qualifier("writeTransactions") TransactionTemplate writes;

  private UUID collection() {
    UUID id=UUID.randomUUID();
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",id,"test-"+id,"Test");
    return id;
  }
  private String base(UUID c) { return "/api/v2/collections/"+c+"/documents"; }
  private JsonNode json(MvcResult result) throws Exception {
    return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }
  private MvcResult writePatch(UUID c,String external,String body,int status) throws Exception {
    return mvc.perform(patch(base(c)+"/by-external-id").param("external_id",external)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status)).andReturn();
  }
  @Test void createsAndReadsDocumentByExactExternalId() throws Exception {
    UUID c=collection(); String external="oai:institución/á 🧬";
    String body="""
        {"metadata":{"year":2025},"texts":[{"text_id":"title/es","kind":"title","language":"es","content":"Ciencia abierta"}]}
        """;
    var created=writePatch(c,external,body,201); var item=json(created);
    assertEquals(external,item.path("external_id").asText());
    assertEquals(1,item.path("revision").asInt());
    assertEquals("\"1\"",created.getResponse().getHeader("ETag"));
    assertEquals("title/es",item.path("texts").get(0).path("text_id").asText());
    mvc.perform(get(base(c)+"/by-external-id").param("external_id",external))
        .andExpect(status().isOk()).andExpect(header().string("ETag","\"1\""))
        .andExpect(jsonPath("$.id").value(item.path("id").asText()));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?",Integer.class,c));
  }

  private MvcResult putDocument(UUID c,String external,String body,int expected) throws Exception {
    return mvc.perform(put(base(c)+"/by-external-id").param("external_id",external)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected)).andReturn();
  }
  private MvcResult find(UUID c,String external,int expected) throws Exception {
    return mvc.perform(get(base(c)+"/by-external-id").param("external_id",external))
        .andExpect(status().is(expected)).andReturn();
  }
  private String id(MvcResult result) throws Exception { return json(result).path("id").asText(); }
  private String body(Object metadata,List<Map<String,Object>> texts) throws Exception {
    var payload=new LinkedHashMap<String,Object>();payload.put("metadata",metadata);payload.put("texts",texts);
    return mapper.writeValueAsString(payload);
  }
  private Map<String,Object> text(String key,String kind,String content) {
    return Map.of("text_id",key,"kind",kind,"content",content);
  }
  private JsonNode textById(JsonNode document,String key) {
    for(var value:document.path("texts")) if(value.path("text_id").asText().equals(key)) return value;
    fail("Text not found in response");return null;
  }
  @Test void collectionScopeAndExactExternalIdsArePreserved() throws Exception {
    UUID a=collection(),b=collection();String external=" oai:Repo/Á 🧬 ";
    var first=writePatch(a,external,body(Map.of("owner","a"),List.of()),201);
    var second=writePatch(b,external,body(Map.of("owner","b"),List.of()),201);
    assertNotEquals(id(first),id(second));
    assertEquals(external,json(find(a,external,200)).path("external_id").asText());
    assertEquals("b",json(find(b,external,200)).path("metadata").path("owner").asText());
    find(a,external.toLowerCase(Locale.ROOT),404);
    mvc.perform(get(base(a)+"/"+id(second))).andExpect(status().isNotFound());
    mvc.perform(delete(base(a)+"/"+id(second))).andExpect(status().isNotFound());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=? AND external_id=?",Integer.class,b,external));
  }
  @Test void patchPutAndNoopRevisionsFollowSnapshotSemantics() throws Exception {
    UUID c=collection();String external="oai:example/1";
    var title=text("title","title","Ciencia"); var abstractText=text("abstract","abstract","Resumen");
    var created=writePatch(c,external,body(Map.of("year",2025),List.of(title,abstractText)),201);
    var original=json(created);assertEquals(1,original.path("revision").asInt());
    String titleHash=textById(original,"title").path("content_hash").asText();
    assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest("Ciencia".getBytes(StandardCharsets.UTF_8))),titleHash);
    var same=json(writePatch(c,external,body(Map.of("year",2025),List.of(title,abstractText)),200));
    assertEquals(1,same.path("revision").asInt());assertEquals(original.path("updated_at"),same.path("updated_at"));
    assertEquals(textById(original,"title").path("updated_at"),textById(same,"title").path("updated_at"));
    var metadataOnly=json(writePatch(c,external,"{\"metadata\":{\"year\":2026}}",200));
    assertEquals(2,metadataOnly.path("revision").asInt());assertEquals(2,metadataOnly.path("texts").size());
    assertEquals(1,textById(metadataOnly,"title").path("revision").asInt());
    var change=json(writePatch(c,external,"{\"texts\":[{\"text_id\":\"title\",\"kind\":\"title\",\"content\":\"Ciencia nueva\"}]}",200));
    assertEquals(3,change.path("revision").asInt());assertEquals(2026,change.path("metadata").path("year").asInt());
    assertEquals(2,textById(change,"title").path("revision").asInt());
    assertNotEquals(titleHash,textById(change,"title").path("content_hash").asText());
    assertEquals(1,textById(change,"abstract").path("revision").asInt());
    var repeat=json(writePatch(c,external,"{\"texts\":[{\"text_id\":\"title\",\"kind\":\"title\",\"content\":\"Ciencia nueva\"}]}",200));
    assertEquals(change.path("updated_at"),repeat.path("updated_at"));assertEquals(3,repeat.path("revision").asInt());
    var replacement=json(putDocument(c,external,body(Map.of(),List.of(abstractText)),200));
    assertEquals(4,replacement.path("revision").asInt());assertEquals(1,replacement.path("texts").size());
    assertEquals("abstract",replacement.path("texts").get(0).path("text_id").asText());
    assertEquals(1,replacement.path("texts").get(0).path("revision").asInt());
    var empty=json(putDocument(c,external,body(Map.of(),List.of()),200));
    assertEquals(5,empty.path("revision").asInt());assertTrue(empty.path("texts").isEmpty());
    assertEquals(5,json(putDocument(c,external,body(Map.of(),List.of()),200)).path("revision").asInt());
  }
  @Test void ifMatchAndOptionalIdempotencyProtectWritesAndDeletes() throws Exception {
    UUID c=collection();String external="precondition/1",path=base(c)+"/by-external-id";
    String body=body(Map.of("n",1),List.of(text("t","title","Text")));
    mvc.perform(patch(path).param("external_id",external).header("If-Match","\"1\"")
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isPreconditionFailed());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?",Integer.class,c));
    String key="write-"+UUID.randomUUID();
    var created=mvc.perform(patch(path).param("external_id",external).header("Idempotency-Key",key)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
    var replay=mvc.perform(patch(path).param("external_id",external).header("Idempotency-Key",key)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
    assertEquals(json(created),json(replay));assertEquals(created.getResponse().getHeader("ETag"),replay.getResponse().getHeader("ETag"));
    assertEquals(created.getResponse().getHeader("Location"),replay.getResponse().getHeader("Location"));
    mvc.perform(patch(path).param("external_id",external).header("Idempotency-Key",key)
        .contentType(MediaType.APPLICATION_JSON).content("{\"metadata\":{\"n\":2}}")).andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("idempotency_conflict"));
    mvc.perform(patch(path).param("external_id",external).header("Idempotency-Key",key).header("If-Match","\"1\"")
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    mvc.perform(patch(path).param("external_id","different").header("Idempotency-Key",key)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    mvc.perform(patch(path).param("external_id",external).header("If-Match","1")
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    mvc.perform(patch(path).param("external_id",external).header("If-Match","\"2\"")
        .contentType(MediaType.APPLICATION_JSON).content("{\"metadata\":{\"n\":2}}"))
        .andExpect(status().isPreconditionFailed());
    var updated=mvc.perform(patch(path).param("external_id",external).header("If-Match","\"1\"")
        .contentType(MediaType.APPLICATION_JSON).content("{\"metadata\":{\"n\":2}}"))
        .andExpect(status().isOk()).andExpect(header().string("ETag","\"2\"")).andReturn();
    String doc=id(updated),deleteKey="delete-"+UUID.randomUUID();
    mvc.perform(delete(base(c)+"/"+doc).header("If-Match","\"1\"")).andExpect(status().isPreconditionFailed());
    mvc.perform(delete(base(c)+"/"+doc).header("If-Match","\"2\"").header("Idempotency-Key",deleteKey))
        .andExpect(status().isNoContent());
    mvc.perform(delete(base(c)+"/"+doc).header("If-Match","\"2\"").header("Idempotency-Key",deleteKey))
        .andExpect(status().isNoContent());
    mvc.perform(delete(base(c)+"/"+doc)).andExpect(status().isNotFound());
    mvc.perform(delete(base(c)+"/"+doc).header("If-Match","\"2\"")).andExpect(status().isPreconditionFailed());
  }
  @Test void metadataNumbersRemainExactAndIdempotencyDistinguishesLargeIntegers() throws Exception {
    UUID c=collection();String external="numbers/1",key="numbers-"+UUID.randomUUID();
    String first="{\"metadata\":{\"n\":9007199254740992,\"fraction\":0.12345678901234567890123456789}}";
    String second="{\"metadata\":{\"n\":9007199254740993,\"fraction\":0.12345678901234567890123456789}}";
    var result=mvc.perform(patch(base(c)+"/by-external-id").param("external_id",external).header("Idempotency-Key",key)
        .contentType(MediaType.APPLICATION_JSON).content(first)).andExpect(status().isCreated()).andReturn();
    assertTrue(result.getResponse().getContentAsString().contains("0.12345678901234567890123456789"));
    assertEquals("0.12345678901234567890123456789",jdbc.queryForObject(
        "SELECT metadata->>'fraction' FROM dvp.documents WHERE collection_id=? AND external_id=?",String.class,c,external));
    mvc.perform(patch(base(c)+"/by-external-id").param("external_id",external).header("Idempotency-Key",key)
        .contentType(MediaType.APPLICATION_JSON).content(second)).andExpect(status().isConflict());
    var sameValue=writePatch(c,external,"{\"metadata\":{\"n\":9007199254740992.0,\"fraction\":0.12345678901234567890123456789}}",200);
    assertEquals(1,json(sameValue).path("revision").asInt());
  }
  @Test void listingsUseSequenceCursorsAndNeverIncludeBodiesByDefault() throws Exception {
    UUID c=collection(),other=collection();
    var ids=new ArrayList<String>();
    for(int i=0;i<3;i++) ids.add(id(writePatch(c,"list/"+i,body(Map.of("n",i),List.of(text("t","title","Body"))),201)));
    String cursor=null;var seen=new ArrayList<String>();
    do {
      var request=get(base(c)).param("limit","1");if(cursor!=null) request.param("cursor",cursor);
      var page=json(mvc.perform(request).andExpect(status().isOk()).andReturn());
      for(var item:page.path("items")) { seen.add(item.path("id").asText());assertFalse(item.has("texts"));assertFalse(item.has("metadata")); }
      cursor=page.path("next_cursor").isNull()?null:page.path("next_cursor").asText();
    } while(cursor!=null);
    assertEquals(ids,seen);
    var page=json(mvc.perform(get(base(c)).param("limit","1").param("include","metadata"))
        .andExpect(status().isOk()).andReturn());
    assertTrue(page.path("items").get(0).has("metadata"));assertFalse(page.path("items").get(0).has("texts"));
    String bound=page.path("next_cursor").asText();
    mvc.perform(get(base(c)).param("cursor",bound)).andExpect(status().isBadRequest());
    mvc.perform(get(base(other)).param("cursor",bound).param("include","metadata")).andExpect(status().isBadRequest());
    mvc.perform(get(base(c)).param("include","texts")).andExpect(status().isBadRequest());
    mvc.perform(get(base(c)).param("limit","0")).andExpect(status().isBadRequest());
    mvc.perform(get(base(UUID.randomUUID()))).andExpect(status().isNotFound());
  }
  @Test void requestAndCombinedDocumentLimitsAreEnforcedAtomically() throws Exception {
    UUID c=collection();String external="limits/1";
    String externalBoundary="🧬".repeat(512),keyBoundary="🧬".repeat(200);
    var accepted=writePatch(c,externalBoundary,body(Map.of(),List.of(text(keyBoundary,"title","x"))),201);
    assertEquals(externalBoundary,json(accepted).path("external_id").asText());
    writePatch(c,"🧬".repeat(513),"{\"texts\":[]}",400);
    writePatch(c,"bad\u0000id","{\"texts\":[]}",400);
    writePatch(c,external,body(Map.of(),List.of(text("🧬".repeat(201),"title","x"))),400);
    writePatch(c,external,body(Map.of(),List.of(text("t","title","x".repeat(129)))),422);
    writePatch(c,external,body(Map.of(),List.of(text("t","title"," "))),422);
    writePatch(c,external,body(Map.of("value","é".repeat(130)),List.of()),413);
    writePatch(c,external,"{\"texts\":[{\"text_id\":\"t\",\"kind\":\"x\",\"content\":\"a\"},{\"text_id\":\"t\",\"kind\":\"x\",\"content\":\"b\"}]}",422);
    writePatch(c,external,"{\"metadata\":null}",400);
    writePatch(c,external,"{\"texts\":null}",400);
    writePatch(c,external,"{\"texts\":[{\"text_id\":\"t\",\"kind\":\"x\",\"content\":\"a\",\"unknown\":true}]}",400);
    putDocument(c,external,"{\"texts\":[]}",400);
    mvc.perform(patch(base(c)+"/by-external-id").param("external_id",external)
        .contentType(MediaType.APPLICATION_JSON).content(" ".repeat(8193))).andExpect(status().isPayloadTooLarge());
    var one=writePatch(c,external,body(Map.of(),List.of(text("a","title","🧬".repeat(128)))),201);
    writePatch(c,external,"{\"texts\":["+mapper.writeValueAsString(text("b","title","🧬".repeat(128)))+"]}",413);
    assertEquals(json(one),json(find(c,external,200)));
    String countId="counts/1";
    writePatch(c,countId,body(Map.of(),List.of(text("a","title","a"),text("b","title","b"))),201);
    writePatch(c,countId,"{\"texts\":["+mapper.writeValueAsString(text("c","title","c"))+","+
        mapper.writeValueAsString(text("d","title","d"))+"]}",422);
    assertEquals(2,json(find(c,countId,200)).path("texts").size());
  }
  @Test void sourceAndMetadataAreRedactedFromDebugOutput(CapturedOutput output) throws Exception {
    UUID c=collection();String marker="private-fixture-"+UUID.randomUUID();
    var created=writePatch(c,"log/1",body(Map.of("note",marker),List.of(text("t","title",marker))),201);
    find(c,"log/1",200);
    mvc.perform(get(base(c)).param("include","metadata")).andExpect(status().isOk());
    assertFalse(output.getAll().contains(marker),"Framework logs must not contain source or metadata values");
  }
  @Test void openApiDescribesDocumentRoutesAndSchemas() throws Exception {
    var schema=json(mvc.perform(get("/api-docs")).andExpect(status().isOk()).andReturn());
    var path=schema.path("paths").path("/api/v2/collections/{collection}/documents/by-external-id");
    assertTrue(path.has("patch"));assertTrue(path.has("put"));assertTrue(path.has("get"));
    assertTrue(schema.path("paths").path("/api/v2/collections/{collection}/documents/{id}").has("delete"));
    var properties=schema.path("components").path("schemas").path("DocumentWriteRequest").path("properties");
    assertTrue(properties.has("metadata"));assertTrue(properties.has("texts"));
    assertTrue(schema.path("components").path("schemas").path("SourceTextInput").path("properties").has("text_id"));
  }

  @Test void concurrentUpsertsAndRevisionChecksAreSerialized() throws Exception {
    UUID c=collection();String external="concurrent/"+UUID.randomUUID();
    String payload=body(Map.of("number",1),List.of(text("t","title","First")));
    var barrier=new CyclicBarrier(2);
    try(var executor=Executors.newFixedThreadPool(2)) {
      var requests=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()->{
        barrier.await(5,TimeUnit.SECONDS);
        return mvc.perform(patch(base(c)+"/by-external-id").param("external_id",external)
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
      })).toList();
      var responses=new ArrayList<MvcResult>();for(var request:requests) responses.add(request.get(10,TimeUnit.SECONDS));
      var statuses=responses.stream().map(r->r.getResponse().getStatus()).sorted().toList();
      assertEquals(List.of(200,201),statuses);assertEquals(id(responses.get(0)),id(responses.get(1)));
      assertEquals(1,json(responses.get(0)).path("revision").asInt());
      assertEquals(1,json(responses.get(1)).path("revision").asInt());
    }
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=? AND external_id=?",Integer.class,c,external));
    var updateBarrier=new CyclicBarrier(2);
    try(var executor=Executors.newFixedThreadPool(2)) {
      var requests=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()->{
        updateBarrier.await(5,TimeUnit.SECONDS);
        return mvc.perform(patch(base(c)+"/by-external-id").param("external_id",external).header("If-Match","\"1\"")
            .contentType(MediaType.APPLICATION_JSON).content("{\"metadata\":{\"number\":"+(i+2)+"}}"))
            .andReturn().getResponse().getStatus();
      })).toList();
      var statuses=new ArrayList<Integer>();for(var request:requests) statuses.add(request.get(10,TimeUnit.SECONDS));
      Collections.sort(statuses);assertEquals(List.of(200,412),statuses);
    }
    assertEquals(2,json(find(c,external,200)).path("revision").asInt());
  }
  @Test void documentLockBlocksConcurrentMutationUntilTheOwnerCommits() throws Exception {
    UUID c=collection();String external="locked/1";
    UUID document=UUID.fromString(id(writePatch(c,external,body(Map.of(),List.of(text("t","title","Text"))),201)));
    var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var executor=Executors.newFixedThreadPool(2)) {
      var owner=executor.submit(()->writes.executeWithoutResult(status->{
        jdbc.queryForObject("SELECT id FROM dvp.documents WHERE id=? FOR UPDATE",UUID.class,document);
        locked.countDown();
        try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("release timed out"); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
      }));
      try {
        assertTrue(locked.await(5,TimeUnit.SECONDS));
        var update=executor.submit(()->writePatch(c,external,"{\"metadata\":{\"changed\":true}}",200));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(()->
            jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%dvp.documents%'",Integer.class)>0);
        assertThrows(TimeoutException.class,()->update.get(200,TimeUnit.MILLISECONDS));
        release.countDown(); owner.get(5,TimeUnit.SECONDS);
        assertEquals(2,json(update.get(5,TimeUnit.SECONDS)).path("revision").asInt());
      } finally { release.countDown(); }
    }
    assertEquals("read committed",writes.execute(status->jdbc.queryForObject("SHOW transaction_isolation",String.class)));
  }
  @Test void concurrentIdempotencyKeysReplayOneResultAndRejectDifferentContent() throws Exception {
    UUID c=collection();String external="idempotent/1",path=base(c)+"/by-external-id",key="same-"+UUID.randomUUID();
    String first=body(Map.of("number",1),List.of(text("t","title","Text")));
    var barrier=new CyclicBarrier(2);
    try(var executor=Executors.newFixedThreadPool(2)) {
      var requests=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()->{
        barrier.await(5,TimeUnit.SECONDS);
        return mvc.perform(patch(path).param("external_id",external).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(first)).andExpect(status().isCreated()).andReturn();
      })).toList();
      var a=requests.get(0).get(10,TimeUnit.SECONDS);var b=requests.get(1).get(10,TimeUnit.SECONDS);
      assertEquals(json(a),json(b));assertEquals(a.getResponse().getHeader("Location"),b.getResponse().getHeader("Location"));
    }
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=? AND external_id=?",Integer.class,c,external));
    String updateKey="update-"+UUID.randomUUID();String update="{\"metadata\":{\"number\":2}}";
    var initial=mvc.perform(patch(path).param("external_id",external).header("Idempotency-Key",updateKey).header("If-Match","\"1\"")
        .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk()).andReturn();
    var replay=mvc.perform(patch(path).param("external_id",external).header("Idempotency-Key",updateKey).header("If-Match","\"1\"")
        .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isOk()).andReturn();
    assertEquals(json(initial),json(replay));assertEquals("\"2\"",replay.getResponse().getHeader("ETag"));
    mvc.perform(patch(path).param("external_id",external).header("Idempotency-Key",updateKey).header("If-Match","\"2\"")
        .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isConflict());
    var conflictBarrier=new CyclicBarrier(2);String other="different-"+UUID.randomUUID(),otherExternal="idempotent/2";
    try(var executor=Executors.newFixedThreadPool(2)) {
      var requests=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()->{
        conflictBarrier.await(5,TimeUnit.SECONDS);
        return mvc.perform(patch(path).param("external_id",otherExternal).header("Idempotency-Key",other)
            .contentType(MediaType.APPLICATION_JSON).content("{\"metadata\":{\"variant\":"+i+"}}"))
            .andReturn().getResponse().getStatus();
      })).toList();
      var statuses=new ArrayList<Integer>();for(var request:requests) statuses.add(request.get(10,TimeUnit.SECONDS));
      Collections.sort(statuses);assertEquals(List.of(201,409),statuses);
    }
  }
  private record VectorFixture(String table,UUID unit,UUID document,UUID source,long revision,UUID indexation,String hash) {}
  private VectorFixture vectorFixture(UUID collection,UUID document,String textKey) throws Exception {
    UUID source=jdbc.queryForObject("SELECT id FROM dvp.source_texts WHERE document_id=? AND text_key=?",UUID.class,document,textKey);
    Long revision=jdbc.queryForObject("SELECT revision FROM dvp.source_texts WHERE id=?",Long.class,source);
    String hash=jdbc.queryForObject("SELECT content_hash FROM dvp.source_texts WHERE id=?",String.class,source);
    UUID profile=UUID.randomUUID(), indexation=UUID.randomUUID(), unit=UUID.randomUUID();
    String table="vec_"+indexation.toString().replace("-","");
    String fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(profile.toString().getBytes(StandardCharsets.UTF_8)));
    jdbc.update("""
        INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint)
        VALUES (?,?,?, ?,3,'import_only','{}'::jsonb,?)
        """,profile,"profile-"+profile,"fixture","v1",fingerprint);
    jdbc.update("""
        INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state)
        VALUES (?,?,?,?, '{}'::jsonb,'{"mode":"none"}'::jsonb,'cosine','none',?,'ready','disabled')
        """,indexation,collection,"index-"+indexation,profile,table);
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid NOT NULL,"
        +"source_revision bigint NOT NULL,indexation_id uuid NOT NULL,embedding vector(3) NOT NULL,input_hash char(64) NOT NULL,"
        +"origin varchar(10) NOT NULL, FOREIGN KEY(unit_id,document_id,source_text_id,source_revision,indexation_id) "
        +"REFERENCES dvp.vector_units(id,document_id,source_text_id,source_revision,indexation_id) ON DELETE CASCADE)");
    jdbc.update("""
        INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash)
        VALUES (?,?,?,?,?,?,0,0,1,?)
        """,unit,collection,indexation,document,source,revision,hash);
    jdbc.update("INSERT INTO dvp."+table+" VALUES (?,?,?,?,?,'[1,0,0]'::vector,?,'imported')",
        unit,document,source,revision,indexation,hash);
    return new VectorFixture(table,unit,document,source,revision,indexation,hash);
  }
  private int vectorCount(VectorFixture fixture) {
    assertTrue(fixture.table().matches("vec_[0-9a-f]{32}"));
    return jdbc.queryForObject("SELECT count(*) FROM dvp."+fixture.table(),Integer.class);
  }
  @Test void textChangesAndDeletesCascadeAcrossIndexationsWithoutCrossCollectionEffects() throws Exception {
    UUID c=collection(),other=collection();String external="cascade/1",textKey="title/pt";
    String payload=body(Map.of("year",2025),List.of(text(textKey,"title","Titulo"),text("abstract","abstract","Resumo")));
    var created=writePatch(c,external,payload,201);UUID document=UUID.fromString(id(created));
    UUID otherDocument=UUID.fromString(id(writePatch(other,external,payload,201)));
    VectorFixture first=vectorFixture(c,document,textKey),second=vectorFixture(c,document,textKey);
    VectorFixture sameCollection=vectorFixture(c,document,"abstract"),otherCollection=vectorFixture(other,otherDocument,textKey);
    var changedMetadata=json(writePatch(c,external,"{\"metadata\":{\"year\":2026}}",200));
    assertEquals(2,changedMetadata.path("revision").asInt());
    assertEquals(1,vectorCount(first));assertEquals(1,vectorCount(second));assertEquals(1,vectorCount(sameCollection));
    String oldHash=textById(changedMetadata,textKey).path("content_hash").asText();
    String change="{\"texts\":[{\"text_id\":\"title/pt\",\"kind\":\"subtitle\",\"language\":\"pt\",\"content\":\"Titulo\"}]}";
    var updated=json(writePatch(c,external,change,200));
    assertEquals(3,updated.path("revision").asInt());
    assertEquals(2,textById(updated,textKey).path("revision").asInt());
    assertEquals(oldHash,textById(updated,textKey).path("content_hash").asText());
    assertEquals(0,vectorCount(first));assertEquals(0,vectorCount(second));
    assertEquals(1,vectorCount(sameCollection));assertEquals(1,vectorCount(otherCollection));
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update(
        "INSERT INTO dvp."+first.table()+" VALUES (?,?,?,?,?,'[1,0,0]'::vector,?,'imported')",
        first.unit(),first.document(),first.source(),first.revision(),first.indexation(),first.hash()));
    VectorFixture newer=vectorFixture(c,document,textKey),newer2=vectorFixture(c,document,textKey);
    String deleteKey="text-delete-"+UUID.randomUUID();
    mvc.perform(delete(base(c)+"/"+document+"/texts").param("text_id",textKey)
        .header("If-Match","\"3\"").header("Idempotency-Key",deleteKey))
        .andExpect(status().isNoContent()).andExpect(header().string("ETag","\"4\""));
    mvc.perform(delete(base(c)+"/"+document+"/texts").param("text_id",textKey)
        .header("If-Match","\"3\"").header("Idempotency-Key",deleteKey)).andExpect(status().isNoContent());
    assertEquals(0,vectorCount(newer));assertEquals(0,vectorCount(newer2));assertEquals(1,vectorCount(sameCollection));
    mvc.perform(delete(base(c)+"/"+document+"/texts").param("text_id",textKey)).andExpect(status().isNotFound());
    assertEquals(4,json(find(c,external,200)).path("revision").asInt());
    mvc.perform(delete(base(c)+"/"+document)).andExpect(status().isNoContent());
    assertEquals(0,vectorCount(sameCollection));assertEquals(1,vectorCount(otherCollection));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE collection_id=?",Integer.class,other));
  }

  @Test void identicalTextsRemainDistinctAndHashesUseExactUtf8() throws Exception {
    UUID c=collection();String external="exact/1",content="Cie\u0301ncia-\nAbierta 🧬";
    var result=json(writePatch(c,external,body(Map.of(),List.of(text("a","title",content),text("b","title",content))),201));
    String a=textById(result,"a").path("content_hash").asText();
    assertEquals(a,textById(result,"b").path("content_hash").asText());
    assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(content.getBytes(StandardCharsets.UTF_8))),a);
    UUID document=UUID.fromString(result.path("id").asText());
    var ids=jdbc.queryForList("SELECT id FROM dvp.source_texts WHERE document_id=? ORDER BY text_key",UUID.class,document);
    assertEquals(2,ids.size());assertNotEquals(ids.get(0),ids.get(1));
    VectorFixture untouched=vectorFixture(c,document,"b");
    writePatch(c,external,"{\"texts\":["+mapper.writeValueAsString(text("a","title","Changed"))+"]}",200);
    assertEquals(1,vectorCount(untouched));
    assertEquals(1,textById(json(find(c,external,200)),"b").path("revision").asInt());
  }
  @Test void putRemovesOnlyOmittedTextVectorsAndNoopPreservesRemainingVector() throws Exception {
    UUID c=collection();String external="put-cascade/1";
    var kept=text("keep","title","Kept");var removed=text("remove","abstract","Removed");
    UUID document=UUID.fromString(id(writePatch(c,external,body(Map.of(),List.of(kept,removed)),201)));
    VectorFixture keptVector=vectorFixture(c,document,"keep"),removedVector=vectorFixture(c,document,"remove");
    String snapshot=body(Map.of(),List.of(kept));
    var updated=mvc.perform(put(base(c)+"/by-external-id").param("external_id",external)
        .contentType(MediaType.APPLICATION_JSON).content(snapshot)).andExpect(status().isOk()).andReturn();
    assertEquals(2,json(updated).path("revision").asInt());
    assertEquals(1,vectorCount(keptVector));assertEquals(0,vectorCount(removedVector));
    var unchanged=mvc.perform(put(base(c)+"/by-external-id").param("external_id",external)
        .contentType(MediaType.APPLICATION_JSON).content(snapshot)).andExpect(status().isOk()).andReturn();
    assertEquals(json(updated),json(unchanged));assertEquals(1,vectorCount(keptVector));
  }
  @Test void databaseFailureRollsBackSourceRevisionAndVectorInvalidation(CapturedOutput output) throws Exception {
    UUID c=collection();String external="rollback/1";
    var initial=writePatch(c,external,body(Map.of(),List.of(text("t","title","Before"))),201);
    UUID document=UUID.fromString(id(initial));VectorFixture fixture=vectorFixture(c,document,"t");
    // The constraint exists only in this disposable test database and forces failure after source mutation.
    jdbc.execute("ALTER TABLE dvp.documents ADD CONSTRAINT phase4_test_metadata_guard "
        +"CHECK (NOT (metadata ? '__phase4_test_forbidden'))");
    String failing="{\"metadata\":{\"__phase4_test_forbidden\":\"private-rollback-marker\"},\"texts\":["+
        mapper.writeValueAsString(text("t","title","After"))+"]}";
    writePatch(c,external,failing,503);
    assertEquals(json(initial),json(find(c,external,200)));
    assertEquals(1,vectorCount(fixture));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.vector_units WHERE id=?",Integer.class,fixture.unit()));
    assertFalse(output.getAll().contains("private-rollback-marker"));
  }
  @Test void upsertAfterConcurrentDeleteCreatesANewDocumentWithoutRevivingVectors() throws Exception {
    UUID c=collection();String external="delete-race/1";
    UUID original=UUID.fromString(id(writePatch(c,external,body(Map.of(),List.of(text("t","title","Before"))),201)));
    VectorFixture fixture=vectorFixture(c,original,"t");
    var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var executor=Executors.newFixedThreadPool(2)) {
      var deletion=executor.submit(()->writes.executeWithoutResult(status->{
        jdbc.queryForObject("SELECT id FROM dvp.documents WHERE id=? FOR UPDATE",UUID.class,original);
        locked.countDown();
        try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("release timed out"); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        jdbc.update("DELETE FROM dvp.documents WHERE id=?",original);
      }));
      try {
        assertTrue(locked.await(5,TimeUnit.SECONDS));
        var replacement=executor.submit(()->mvc.perform(patch(base(c)+"/by-external-id").param("external_id",external)
            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of(),List.of(text("t","title","After")))))
            .andReturn());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(()->
            jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%dvp.documents%'",Integer.class)>0);
        assertThrows(TimeoutException.class,()->replacement.get(200,TimeUnit.MILLISECONDS));
        release.countDown();deletion.get(5,TimeUnit.SECONDS);
        var response=replacement.get(5,TimeUnit.SECONDS);
        assertEquals(201,response.getResponse().getStatus());assertNotEquals(original.toString(),id(response));
        assertEquals(1,json(response).path("revision").asInt());assertEquals(0,vectorCount(fixture));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=? AND external_id=?",Integer.class,c,external));
      } finally { release.countDown(); }
    }
  }
}
