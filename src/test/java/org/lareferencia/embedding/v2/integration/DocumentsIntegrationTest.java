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
        {"metadata":{"year":2025},"texts":[{"knd":"title","lng":"es","cnt":"Ciencia abierta"}]}
        """;
    var created=writePatch(c,external,body,201); var item=json(created);
    assertEquals(external,item.path("external_id").asText());
    assertEquals(1,item.path("revision").asInt());
    assertEquals("\"1\"",created.getResponse().getHeader("ETag"));
    assertEquals("title",item.path("texts").get(0).path("knd").asText());
    mvc.perform(get(base(c)+"/by-external-id").param("external_id",external))
        .andExpect(status().isOk()).andExpect(header().string("ETag","\"1\""))
        .andExpect(jsonPath("$.id").value(item.path("id").asText()));
    mvc.perform(get(base(c)).param("external_id",external)).andExpect(status().isBadRequest());
    mvc.perform(get(base(c)).param("external_id",external).param("limit","1")).andExpect(status().isBadRequest());
    mvc.perform(get(base(c)+"/by-external-id").param("external_id","missing")).andExpect(status().isNotFound());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?",Integer.class,c));
  }

  private MvcResult putDocument(UUID c,String external,String body,int expected) throws Exception {
    return mvc.perform(put(base(c)+"/by-external-id").param("external_id",external)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(expected)).andReturn();
  }
  @Test void compactTextsWorkForPatchPutAndReads() throws Exception {
    UUID c=collection();String external="compact-document";
    String initial="{\"metadata\":{},\"texts\":[{\"knd\":\"title\",\"lng\":\"pt\",\"cnt\":\"Acesso aberto\"}]}";
    var created=json(writePatch(c,external,initial,201));
    assertEquals("title",created.path("texts").get(0).path("knd").asText());
    assertFalse(created.path("texts").get(0).has("text_id"));
    String changed="{\"texts\":[{\"knd\":\"title\",\"lng\":\"pt\",\"cnt\":\"Novo título\"}]}";
    var updated=json(writePatch(c,external,changed,200));
    assertEquals(1,updated.path("texts").size());
    assertEquals("Novo título",updated.path("texts").get(0).path("cnt").asText());
    String replacement="{\"metadata\":{},\"texts\":[{\"knd\":\"abstract\",\"cnt\":\"Resumo\"}]}";
    var replaced=json(putDocument(c,external,replacement,200));
    assertEquals(1,replaced.path("texts").size());
    assertEquals("abstract",replaced.path("texts").get(0).path("knd").asText());
    assertEquals("Resumo",json(find(c,external,200)).path("texts").get(0).path("cnt").asText());
    mvc.perform(delete(base(c)+"/"+created.path("id").asText()+"/texts").param("knd","abstract"))
        .andExpect(status().isNoContent());
    assertTrue(json(find(c,external,200)).path("texts").isEmpty());
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
    return Map.of("knd",key,"cnt",content);
  }
  private JsonNode textById(JsonNode document,String key) {
    for(var value:document.path("texts")) if(value.path("knd").asText().equals(key)) return value;
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
    var change=json(writePatch(c,external,"{\"texts\":[{\"knd\":\"title\",\"cnt\":\"Ciencia nueva\"}]}",200));
    assertEquals(3,change.path("revision").asInt());assertEquals(2026,change.path("metadata").path("year").asInt());
    assertEquals(2,textById(change,"title").path("revision").asInt());
    assertNotEquals(titleHash,textById(change,"title").path("content_hash").asText());
    assertEquals(1,textById(change,"abstract").path("revision").asInt());
    var repeat=json(writePatch(c,external,"{\"texts\":[{\"knd\":\"title\",\"cnt\":\"Ciencia nueva\"}]}",200));
    assertEquals(change.path("updated_at"),repeat.path("updated_at"));assertEquals(3,repeat.path("revision").asInt());
    var replacement=json(putDocument(c,external,body(Map.of(),List.of(abstractText)),200));
    assertEquals(4,replacement.path("revision").asInt());assertEquals(1,replacement.path("texts").size());
    assertEquals("abstract",replacement.path("texts").get(0).path("knd").asText());
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
    String externalBoundary="🧬".repeat(512),keyBoundary="title";
    var accepted=writePatch(c,externalBoundary,body(Map.of(),List.of(text(keyBoundary,"title","x"))),201);
    assertEquals(externalBoundary,json(accepted).path("external_id").asText());
    writePatch(c,"🧬".repeat(513),"{\"texts\":[]}",400);
    writePatch(c,"bad\u0000id","{\"texts\":[]}",400);
    writePatch(c,external,body(Map.of(),List.of(text("🧬".repeat(61),"title","x"))),400);
    writePatch(c,external,body(Map.of(),List.of(text("t","title","x".repeat(129)))),422);
    writePatch(c,external,body(Map.of(),List.of(text("t","title"," "))),422);
    writePatch(c,external,body(Map.of("value","é".repeat(130)),List.of()),413);

    writePatch(c,external,"{\"metadata\":null}",400);
    writePatch(c,external,"{\"texts\":null}",400);
    writePatch(c,external,"{\"texts\":[{\"knd\":\"x\",\"cnt\":\"a\",\"unknown\":true}]}",400);
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
    assertTrue(schema.path("components").path("schemas").path("SourceTextInput").path("properties").has("knd"));
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
  @Test void failedWritePreservesDocumentSnapshot() throws Exception {
    UUID c=collection();String external="rollback-compact";
    var initial=writePatch(c,external,body(Map.of(),List.of(text("title","title","Before"))),201);
    jdbc.execute("ALTER TABLE dvp.documents ADD CONSTRAINT compact_test_metadata_guard CHECK (NOT (metadata ? '__compact_forbidden'))");
    String failing="{\"metadata\":{\"__compact_forbidden\":true},\"texts\":[{\"knd\":\"title\",\"cnt\":\"After\"}]}";
    writePatch(c,external,failing,503);
    assertEquals(json(initial),json(find(c,external,200)));
  }
  @Test void concurrentDeleteAndUpsertCreatesNewDocument() throws Exception {
    UUID c=collection();String external="delete-race-compact";
    UUID original=UUID.fromString(id(writePatch(c,external,body(Map.of(),List.of(text("title","title","Before"))),201)));
    var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
    try(var executor=Executors.newFixedThreadPool(2)) {
      var deletion=executor.submit(()->writes.executeWithoutResult(status->{
        jdbc.queryForObject("SELECT id FROM dvp.documents WHERE id=? FOR UPDATE",UUID.class,original);locked.countDown();
        try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        jdbc.update("DELETE FROM dvp.documents WHERE id=?",original);
      }));
      try {
        assertTrue(locked.await(5,TimeUnit.SECONDS));
        var replacement=executor.submit(()->writePatch(c,external,body(Map.of(),List.of(text("title","title","After"))),201));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(()->jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%dvp.documents%'",Integer.class)>0);
        release.countDown();deletion.get(5,TimeUnit.SECONDS);
        assertNotEquals(original.toString(),id(replacement.get(5,TimeUnit.SECONDS)));
      } finally {release.countDown();}
    }
  }
}
