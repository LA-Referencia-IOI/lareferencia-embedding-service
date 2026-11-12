package org.lareferencia.embedding.v2.search;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.workers-enabled=false","app.dvp.import-enabled=true"})
class SearchIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");}
  @Autowired SearchService service;@Autowired ObjectMapper mapper;
  @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
  @Autowired org.lareferencia.embedding.v2.config.DvpProperties props;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @org.springframework.test.context.bean.override.mockito.MockitoBean org.lareferencia.embedding.v2.profile.ProviderRegistry providers;
  @Autowired org.lareferencia.embedding.v2.job.JobRepository jobs;
  @Autowired @Qualifier("readTransactions") org.springframework.transaction.support.TransactionTemplate reads;
  @Autowired @Qualifier("readJdbc") JdbcTemplate readJdbc;@Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  record Fixture(UUID collection,UUID index,String table) {}
  Fixture fixture(String metric,String strategy) {
    UUID c=UUID.randomUUID(),p=UUID.randomUUID(),i=UUID.randomUUID();String table="vec_"+i.toString().replace("-","");
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",c,"c-"+c,"test");
    jdbc.update("INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint) VALUES (?,?,'test','v1',2,'import_only','{\"version\":\"identity-v1\",\"document_prefix\":\"\",\"query_prefix\":\"\"}',?)",p,"p-"+p,p.toString().replace("-","").repeat(2));
    jdbc.update("INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state) VALUES (?,?,?,?,'{}','{\"mode\":\"none\"}',?,?,?,'ready',?)",i,c,"index",p,metric,strategy,table,strategy.equals("none")?"disabled":"ready");
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid NOT NULL,source_revision bigint NOT NULL,indexation_id uuid NOT NULL,embedding vector(2) NOT NULL,origin varchar(10) NOT NULL)");
    if(!strategy.equals("none")) {String op=switch(metric){case "l2"->"l2";case "inner_product"->"ip";default->"cosine";};String expr=strategy.equals("hnsw_halfvec")?"(embedding::halfvec(2)) halfvec_"+op+"_ops":"embedding vector_"+op+"_ops";jdbc.execute("CREATE INDEX "+table+"_ann ON dvp."+table+" USING hnsw ("+expr+")");}
    return new Fixture(c,i,table);
  }
  UUID document(Fixture f,String external) {UUID d=UUID.randomUUID();jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id,metadata) VALUES (?,?,?,?::jsonb)",d,f.collection(),external,"{\"year\":2025}");return d;}
  void unit(Fixture f,UUID d,int ordinal,String vector) {UUID t=UUID.randomUUID(),u=UUID.randomUUID();jdbc.update("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,content,content_hash) VALUES (?,?,?,'abstract','hello',?)",t,d,"text-"+ordinal,"a".repeat(64));jdbc.update("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash) VALUES (?,?,?,?,?,1,0,0,5,?)",u,f.collection(),f.index(),d,t,"b".repeat(64));jdbc.update("INSERT INTO dvp."+f.table()+" VALUES (?,?,?,?,?,?::vector,'generated')",u,d,t,1,f.index(),vector);}
  Map<String,Object> search(Fixture f,String json)throws Exception {return service.search(f.collection().toString(),f.index().toString(),mapper.readTree(json));}
  @Test void exactGroupsDocumentsBeforeLimitAndIncludesProjections() throws Exception {
    Fixture f=fixture("l2","hnsw_vector");UUID a=document(f,"a"),b=document(f,"b");unit(f,a,1,"[0.1,0]");unit(f,a,2,"[0.2,0]");unit(f,b,1,"[1,0]");
    var result=search(f,"{\"query\":{\"vector\":[0,0]},\"mode\":\"exact\",\"top_n\":2,\"include\":[\"texts\",\"metadata\"]}");var items=(List<?>)result.get("results");assertEquals(2,items.size());assertEquals(a,((Map<?,?>)items.get(0)).get("document_id"));assertEquals(b,((Map<?,?>)items.get(1)).get("document_id"));assertEquals("hello",((Map<?,?>)items.get(0)).get("text"));assertEquals(true,result.get("recall_guaranteed"));
    var u=search(f,"{\"query\":{\"vector\":[0,0]},\"mode\":\"exact\",\"result_unit\":\"unit\",\"top_n\":10}");assertEquals(3,((List<?>)u.get("results")).size());assertEquals("exhausted",u.get("shortfall_reason"));
  }
  @Test void approximateAndHalfvecSearchAndUnavailable() throws Exception {
    for(String strategy:List.of("hnsw_vector","hnsw_halfvec")){Fixture f=fixture("cosine",strategy);UUID d=document(f,"a");unit(f,d,1,"[1,0]");
      var result=search(f,"{\"query\":{\"vector\":[1,0]},\"top_n\":2,\"include\":[\"texts\",\"metadata\"]}");assertEquals(1,((List<?>)result.get("results")).size());assertEquals("hello",((Map<?,?>)((List<?>)result.get("results")).getFirst()).get("text"));assertTrue(((Map<?,?>)((List<?>)result.get("results")).getFirst()).containsKey("metadata"));assertEquals("candidate_limit",result.get("shortfall_reason"));assertEquals(false,result.get("recall_guaranteed"));
      jdbc.update("UPDATE dvp.indexations SET ann_state='pending' WHERE id=?",f.index());assertEquals("approximate_search_unavailable",assertThrows(V2Exception.class,()->search(f,"{\"query\":{\"vector\":[1,0]}} ")).code());
    }
  }
  @Test void validationAndImportOnly() throws Exception {Fixture f=fixture("cosine","none");
    assertEquals("invalid_vector",assertThrows(V2Exception.class,()->search(f,"{\"query\":{\"vector\":[0,0]},\"mode\":\"exact\"}")).code());
    assertEquals("profile_not_executable",assertThrows(V2Exception.class,()->search(f,"{\"query\":{\"text\":\"hello\"},\"mode\":\"exact\"}")).code());
    assertEquals("approximate_search_unavailable",assertThrows(V2Exception.class,()->search(f,"{\"query\":{\"vector\":[1,0]}}")).code());
  }
  @Test void blockedTextInferenceRespectsAdmissionAndKeepsHeartbeatAndWritesAvailable() throws Exception {
    Fixture f=fixture("cosine","none");UUID d=document(f,"a");unit(f,d,1,"[1,0]");
    jdbc.update("UPDATE dvp.embedding_profiles SET execution_mode='http',provider_ref='test',preparation='{\"version\":\"identity-v1\",\"document_prefix\":\"\",\"query_prefix\":\"query: \"}' WHERE id=(SELECT profile_id FROM dvp.indexations WHERE id=?)",f.index());
    var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.when(providers.embed(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv->{assertEquals(List.of("query: hello"),inv.getArgument(1));entered.countDown();assertTrue(release.await(8,java.util.concurrent.TimeUnit.SECONDS));return List.of(new float[]{1,0});});
    UUID job=UUID.randomUUID();jdbc.update("INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters) VALUES (?,?,'prepare','queued','{}')",job,f.collection());var lease=jobs.claim("search-test-"+UUID.randomUUID(),Set.of("prepare")).orElseThrow();
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a=executor.submit(()->search(f,"{\"query\":{\"text\":\"hello\"},\"mode\":\"exact\"}"));var b=executor.submit(()->search(f,"{\"query\":{\"text\":\"hello\"},\"mode\":\"exact\"}"));
      try {assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(429,assertThrows(V2Exception.class,()->search(f,"{\"query\":{\"vector\":[1,0]},\"mode\":\"exact\"}")).status());
        assertTrue(jobs.heartbeat(lease));assertEquals(1,jdbc.update("UPDATE dvp.documents SET metadata='{}' WHERE id=?",d));
      } finally {release.countDown();}
      assertEquals(1,((List<?>)a.get(10,java.util.concurrent.TimeUnit.SECONDS).get("results")).size());assertEquals(1,((List<?>)b.get(10,java.util.concurrent.TimeUnit.SECONDS).get("results")).size());
    }
    assertEquals(1,((List<?>)search(f,"{\"query\":{\"vector\":[1,0]},\"mode\":\"exact\"}").get("results")).size());
  }

  @Test void annCandidateExpansionMatchesExactBaselineAndPlansUseExpectedIndexes() throws Exception {
    Fixture f=fixture("l2","hnsw_vector");UUID dominant=document(f,"dominant");for(int i=0;i<140;i++)unit(f,dominant,i,"[0.01,0]");
    for(int i=0;i<12;i++){UUID d=document(f,"doc-"+i);unit(f,d,0,"["+(i+1)+",0]");}
    var exact=search(f,"{\"query\":{\"vector\":[0,0]},\"mode\":\"exact\",\"top_n\":10}");var approx=search(f,"{\"query\":{\"vector\":[0,0]},\"top_n\":10}");
    var expected=(List<?>)exact.get("results");var actual=(List<?>)approx.get("results");assertEquals(10,actual.size());
    var exactIds=expected.stream().map(x->((Map<?,?>)x).get("document_id")).collect(java.util.stream.Collectors.toSet());var actualIds=actual.stream().map(x->((Map<?,?>)x).get("document_id")).collect(java.util.stream.Collectors.toSet());
    exactIds.retainAll(actualIds);assertTrue(exactIds.size()>=8,"recall@10 must be at least 0.8 on this synthetic corpus");
    reads.execute(status->{readJdbc.execute("SET LOCAL enable_seqscan = off");
      String ann=String.join(" ",readJdbc.queryForList("EXPLAIN SELECT unit_id FROM dvp."+f.table()+" ORDER BY embedding <-> '[0,0]'::vector LIMIT 100",String.class));assertTrue(ann.contains(f.table()+"_ann"),ann);
      String exactPlan=String.join(" ",readJdbc.queryForList("EXPLAIN WITH distances AS MATERIALIZED (SELECT unit_id,embedding <-> '[0,0]'::vector distance FROM dvp."+f.table()+") SELECT * FROM distances ORDER BY distance LIMIT 10",String.class));assertFalse(exactPlan.contains(f.table()+"_ann"),exactPlan);return null;});
  }
  @Test void halfvecQueryAdmissionAndStaleRows() throws Exception {
    Fixture f=fixture("cosine","hnsw_halfvec");UUID d=document(f,"a");unit(f,d,0,"[1,0]");
    assertEquals("halfvec_not_representable",assertThrows(V2Exception.class,()->search(f,"{\"query\":{\"vector\":[70000,1]}} ")).code());
    assertEquals("halfvec_not_representable",assertThrows(V2Exception.class,()->search(f,"{\"query\":{\"vector\":[0.00000001,0]}} ")).code());
    assertEquals(1,((List<?>)search(f,"{\"query\":{\"vector\":[70000,1]},\"mode\":\"exact\"}").get("results")).size());
    jdbc.update("UPDATE dvp.source_texts SET revision=2 WHERE document_id=?",d);
    assertTrue(((List<?>)search(f,"{\"query\":{\"vector\":[1,0]},\"mode\":\"exact\"}").get("results")).isEmpty());
    assertTrue(((List<?>)search(f,"{\"query\":{\"vector\":[1,0]}}").get("results")).isEmpty());
  }

  @Test void httpExactSearchAndMetricOrdering() throws Exception {
    for(String metric:List.of("cosine","l2","inner_product")) {
      Fixture f=fixture(metric,"none");UUID best=document(f,"best"),other=document(f,"other");unit(f,best,0,"[1,0]");unit(f,other,0,"[0,1]");
      var response=search(f,"{\"query\":{\"vector\":[1,0]},\"mode\":\"exact\",\"top_n\":2}");assertEquals(best,((Map<?,?>)((List<?>)response.get("results")).getFirst()).get("document_id"));
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/collections/"+f.collection()+"/indexations/"+f.index()+"/search").contentType("application/json").content("{\"query\":{\"vector\":[1,0]},\"mode\":\"exact\"}"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.results.length()").value(2));
    }
  }

  @Test void exactTimeoutReturns503AndReleasesAdmission() throws Exception {
    Fixture f=fixture("l2","none");UUID d=document(f,"slow");unit(f,d,0,"[1,0]");
    jdbc.execute("ALTER TABLE dvp."+f.table()+" RENAME TO "+f.table()+"_underlying");
    jdbc.execute("CREATE VIEW dvp."+f.table()+" AS SELECT t.* FROM dvp."+f.table()+"_underlying t CROSS JOIN LATERAL (SELECT pg_sleep(2)) delay");
    int previous=props.getExactTimeoutSeconds();props.setExactTimeoutSeconds(1);
    try {var failure=assertThrows(V2Exception.class,()->search(f,"{\"query\":{\"vector\":[1,0]},\"mode\":\"exact\"}"));assertEquals(503,failure.status());assertEquals("search_timeout",failure.code());}
    finally {props.setExactTimeoutSeconds(previous);}
    Fixture fast=fixture("l2","none");UUID fastDoc=document(fast,"fast");unit(fast,fastDoc,0,"[1,0]");assertEquals(1,((List<?>)search(fast,"{\"query\":{\"vector\":[1,0]},\"mode\":\"exact\"}").get("results")).size());
  }

  @Test void searchTelemetryUsesBoundedLabels() throws Exception {
    Fixture f=fixture("l2","hnsw_vector");UUID d=document(f,"sensitive-document-value");unit(f,d,0,"[1,0]");
    double before=metrics.find("dvp.search.duration").tag("mode","approximate").tag("outcome","success").timer()==null?0:metrics.find("dvp.search.duration").tag("mode","approximate").tag("outcome","success").timer().count();
    search(f,"{\"query\":{\"vector\":[1,0]},\"top_n\":2}");
    assertEquals(before+1,metrics.find("dvp.search.duration").tag("mode","approximate").tag("outcome","success").timer().count());
    assertNotNull(metrics.find("dvp.search.candidates").tag("mode","approximate").summary());assertNotNull(metrics.find("dvp.search.shortfalls").tag("mode","approximate").counter());
    for(var meter:metrics.getMeters())if(meter.getId().getName().startsWith("dvp.search"))assertFalse(meter.getId().getTags().toString().contains("sensitive-document-value"));
  }

  @Test void apiErrorMetricsUseAllowlistedCodes() throws Exception {
    Fixture f=fixture("cosine","none");var before=metrics.find("dvp.api.errors").tag("code","invalid_vector").counter();double count=before==null?0:before.count();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/collections/"+f.collection()+"/indexations/"+f.index()+"/search").contentType("application/json").content("{\"query\":{\"vector\":[0,0]},\"mode\":\"exact\"}"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());
    assertEquals(count+1,metrics.find("dvp.api.errors").tag("code","invalid_vector").counter().count());
  }

  @Test void openApiAdvertisesDeliveredV2Routes() throws Exception {
    var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api-docs")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
    var paths=mapper.readTree(response).path("paths");
    var expected=Map.ofEntries(
      Map.entry("/api/v2/collections","post"),Map.entry("/api/v2/collections/{collection}/documents/by-external-id","patch"),
      Map.entry("/api/v2/embedding-profiles","post"),Map.entry("/api/v2/collections/{collection}/indexations","post"),
      Map.entry("/api/v2/collections/{collection}/indexations/{id}/maintenance-jobs","post"),
      Map.entry("/api/v2/collections/{collection}/indexations/{indexation}/preparation-jobs","post"),
      Map.entry("/api/v2/collections/{collection}/indexations/{indexation}/generation-jobs","post"),
      Map.entry("/api/v2/collections/{collection}/imports","post"),Map.entry("/api/v2/jobs/{id}/retries","post"),
      Map.entry("/api/v2/collections/{collection}/documents/{document}/vectors","get"),
      Map.entry("/api/v2/collections/{collection}/document-vectors","get"),
      Map.entry("/api/v2/collections/{collection}/indexations/{indexation}/documents/{document}/vectors","get"),
      Map.entry("/api/v2/collections/{collection}/indexations/{indexation}/document-vectors","get"),
      Map.entry("/api/v2/collections/{collection}/indexations/{indexation}/search","post"));
    expected.forEach((path,method)->assertTrue(paths.path(path).has(method),"OpenAPI missing "+method+" "+path));
  }

  @Test void openApiGroupsVersionsInSwaggerOrderAndUsesNewDocumentUrl() throws Exception {
    var body=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api-docs"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
    var api=mapper.readTree(body);var tags=new ArrayList<String>();api.path("tags").forEach(tag->tags.add(tag.path("name").asText()));
    assertTrue(tags.contains("v2 Indexations"));assertTrue(tags.contains("v1 Stores"));
    boolean sawV1=false;for(String tag:tags) {assertTrue(tag.startsWith("v1 ") || tag.startsWith("v2 "),tag);if(tag.startsWith("v1 "))sawV1=true;else assertFalse(sawV1,"v2 tag appeared after v1");}assertTrue(sawV1);
    api.path("paths").fields().forEachRemaining(path->{String prefix=path.getKey().startsWith("/api/v2/")?"v2 ":path.getKey().startsWith("/api/v1/")?"v1 ":null;
      if(prefix!=null) path.getValue().fields().forEachRemaining(operation->{if(operation.getValue().isObject() && operation.getValue().has("operationId")) {
        var operationTags=operation.getValue().path("tags");assertTrue(operationTags.isArray() && !operationTags.isEmpty(),path.getKey());operationTags.forEach(tag->assertTrue(tag.asText().startsWith(prefix),path.getKey()));
      }});
    });
    var config=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api-docs/swagger-config"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
    assertEquals("/api-docs",mapper.readTree(config).path("url").asText());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
  }

}
