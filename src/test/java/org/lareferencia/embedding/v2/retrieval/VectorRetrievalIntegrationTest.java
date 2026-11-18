package org.lareferencia.embedding.v2.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.util.LinkedMultiValueMap;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.workers-enabled=false","app.dvp.read-connections=1"})
class VectorRetrievalIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");}
  @Autowired VectorRetrievalService service; @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired @Qualifier("readTransactions") org.springframework.transaction.support.TransactionTemplate reads;
  @Autowired @Qualifier("readJdbc") JdbcTemplate readJdbc;
  @Autowired @Qualifier("readDataSource") javax.sql.DataSource readDataSource;
  @Autowired @Qualifier("coordinationJdbc") JdbcTemplate coordinationJdbc;
  @Autowired org.lareferencia.embedding.v2.job.JobRepository jobs;
  record Fixture(UUID collection,UUID doc,UUID index,UUID text,String table) {}
  Fixture fixture() {
    UUID c=UUID.randomUUID(),d=UUID.randomUUID(),p=UUID.randomUUID(),i=UUID.randomUUID(),s=UUID.randomUUID();String table="vec_"+i.toString().replace("-","");
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",c,"c-"+c,"test");
    jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id,metadata) VALUES (?,?,'doc','{\"year\":2024}')",d,c);
    jdbc.update("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,language,content,content_hash) VALUES (?,?,'abstract','abstract','en','hello world',?)",s,d,"a".repeat(64));
    jdbc.update("INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint) VALUES (?,?,?,'v1',2,'import_only','{}',?)",p,"p-"+p,"model",p.toString().replace("-","").repeat(2));
    jdbc.update("INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state) VALUES (?,?,?,?,'{}','{\"mode\":\"none\"}','cosine','none',?,'ready','disabled')",i,c,"idx",p,table);
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,indexation_id uuid NOT NULL,embedding vector(2) NOT NULL,origin varchar(10) NOT NULL)");
    return new Fixture(c,d,i,s,table);
  }
  void unit(Fixture f,boolean vector,int ordinal) {
    UUID u=UUID.randomUUID();jdbc.update("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,ordinal,start_cp,end_cp,input_hash,prepared_text,prepared_key) VALUES (?,?,?,?,?,0,5,?,'hello',?)",u,f.collection(),f.index(),f.doc(),ordinal,"a".repeat(64),u.toString());
    if(vector)jdbc.update("INSERT INTO dvp."+f.table()+" VALUES (?,?,?,'[1,2]','generated')",u,f.doc(),f.index());
  }
  @Test void openAiShapePaginationAndExternalResolution() {
    Fixture f=fixture();unit(f,true,0);unit(f,true,1);unit(f,false,2);
    jdbc.update("UPDATE dvp."+f.table()+" SET embedding='[3,4]' WHERE unit_id IN (SELECT id FROM dvp.vector_units WHERE document_id=? AND ordinal=1)",f.doc());
    var all=service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,new LinkedMultiValueMap<>());
    assertEquals("list",all.get("object"));assertEquals("model",all.get("model"));assertEquals(Set.of("object","data","model"),all.keySet());
    var data=(List<?>)all.get("data");assertEquals(2,data.size());
    var item=(Map<?,?>)data.getFirst();assertEquals("embedding",item.get("object"));assertEquals(0,item.get("index"));
    assertEquals(1,((Map<?,?>)data.get(1)).get("index"));assertEquals(2,((com.fasterxml.jackson.databind.JsonNode)item.get("embedding")).size());
    var q=new LinkedMultiValueMap<String,String>();q.add("limit","1");
    var page=service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,q);
    assertEquals(1,((List<?>)page.get("data")).size());assertNotNull(page.get("next_cursor"));
    q.add("cursor",page.get("next_cursor").toString());var second=service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,q);
    assertEquals(1,((List<?>)second.get("data")).size());assertNull(second.get("next_cursor"));
    assertNotEquals(((Map<?,?>)((List<?>)page.get("data")).getFirst()).get("embedding"),((Map<?,?>)((List<?>)second.get("data")).getFirst()).get("embedding"));
    var ext=new LinkedMultiValueMap<String,String>();ext.add("external_id","doc");
    assertEquals(all,service.get(f.collection().toString(),f.index().toString(),null,true,ext));
  }
  @Test void emptyMissingAndUnavailable() {
    Fixture f=fixture();var q=new LinkedMultiValueMap<String,String>();var empty=service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,q);
    assertEquals(List.of(),empty.get("data"));assertEquals("model",empty.get("model"));
    jdbc.update("UPDATE dvp.indexations SET state='failed' WHERE id=?",f.index());
    assertEquals(409,assertThrows(V2Exception.class,()->service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,q)).status());
    assertEquals(404,assertThrows(V2Exception.class,()->service.get(f.collection().toString(),f.index().toString(),UUID.randomUUID().toString(),false,q)).status());
  }
  @Test void defaultResponseIncludesMoreThanTheFormerPageSize() {
    Fixture f=fixture();for(int n=0;n<101;n++)unit(f,true,n);
    var response=service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,new LinkedMultiValueMap<>());
    assertEquals(101,((List<?>)response.get("data")).size());assertFalse(response.containsKey("next_cursor"));
  }
  @Test void indexationHttpRoutesAndStaleRevision() throws Exception {
    Fixture f=fixture();unit(f,true,0);String c=f.collection().toString(),d=f.doc().toString(),i=f.index().toString();
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/collections/"+c+"/indexations/"+i+"/vectors"))
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    for(String path:List.of("/api/v2/collections/"+c+"/indexations/"+i+"/documents/"+d+"/vectors", "/api/v2/collections/"+c+"/indexations/"+i+"/vectors?external_id=doc"))
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.object").value("list")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.length()").value(1)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].object").value("embedding")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].index").value(0)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.model").value("model")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.indexations").doesNotExist());
    for(String path:List.of("/api/v2/collections/"+c+"/documents/"+d+"/vectors", "/api/v2/collections/"+c+"/document-vectors?external_id=doc", "/api/v2/collections/"+c+"/vectors?external_id=doc", "/api/v2/collections/"+c+"/indexations/"+i+"/document-vectors?external_id=doc"))
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    jdbc.update("UPDATE dvp.source_texts SET revision=2 WHERE id=?",f.text());
    var result=service.get(c,i,d,false,new LinkedMultiValueMap<>());assertEquals(1,((List<?>)result.get("data")).size());
  }
  @Test void readPoolExhaustionReturns503WhileWriteAndCoordinationContinue() throws Exception {
    Fixture f=fixture();
    UUID job=UUID.randomUUID();jdbc.update("INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters) VALUES (?,?,'prepare','queued','{}')",job,f.collection());
    var lease=jobs.claim("retrieval-test-"+UUID.randomUUID(),Set.of("prepare")).orElseThrow();
    try(var held=readDataSource.getConnection()) {
      long start=System.nanoTime();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/collections/"+f.collection()+"/indexations/"+f.index()+"/documents/"+f.doc()+"/vectors")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
      assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toSeconds()<5);
      assertEquals(1,jdbc.update("UPDATE dvp.documents SET metadata='{}' WHERE id=?",f.doc()));
      assertEquals(1,coordinationJdbc.queryForObject("SELECT 1",Integer.class));
      assertTrue(jobs.heartbeat(lease));
    }
  }

  @Test void multipleDimensionsAndUnselectedTextsHaveIndependentCoverage() {
    Fixture f=fixture();unit(f,true,0);UUID profile=UUID.randomUUID(),index=UUID.randomUUID(),unit=UUID.randomUUID();String table="vec_"+index.toString().replace("-","");
    jdbc.update("INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint) VALUES (?,?,?,'v1',3,'import_only','{}',?)",profile,"p-"+profile,"model3",profile.toString().replace("-","").repeat(2));
    jdbc.update("INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state) VALUES (?,?,?,?,'{}','{\"mode\":\"none\"}','cosine','none',?,'ready','disabled')",index,f.collection(),"second",profile,table);
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,indexation_id uuid NOT NULL,embedding vector(3) NOT NULL,origin varchar(10) NOT NULL)");
    jdbc.update("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,ordinal,start_cp,end_cp,input_hash,prepared_text,prepared_key) VALUES (?,?,?,?,0,0,5,?,'hello',?)",unit,f.collection(),index,f.doc(),"b".repeat(64),unit.toString());
    jdbc.update("INSERT INTO dvp."+table+" VALUES (?,?,?,'[1,2,3]','generated')",unit,f.doc(),index);
    var q=new LinkedMultiValueMap<String,String>();var result=service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,q);
    assertEquals("model",result.get("model"));assertEquals(1,((List<?>)result.get("data")).size());
    var other=service.get(f.collection().toString(),index.toString(),f.doc().toString(),false,q);
    assertEquals("model3",other.get("model"));
    var embedding=(com.fasterxml.jackson.databind.JsonNode)((Map<?,?>)((List<?>)other.get("data")).getFirst()).get("embedding");assertEquals(3,embedding.size());
  }
}
