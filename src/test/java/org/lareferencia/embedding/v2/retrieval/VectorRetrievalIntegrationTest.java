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
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid NOT NULL,source_revision bigint NOT NULL,indexation_id uuid NOT NULL,embedding vector(2) NOT NULL,origin varchar(10) NOT NULL)");
    return new Fixture(c,d,i,s,table);
  }
  void unit(Fixture f,boolean vector,int ordinal) {
    UUID u=UUID.randomUUID();jdbc.update("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash) VALUES (?,?,?,?,?,1,?,0,5,?)",u,f.collection(),f.index(),f.doc(),f.text(),ordinal,"a".repeat(64));
    if(vector)jdbc.update("INSERT INTO dvp."+f.table()+" VALUES (?,?,?,?,?,'[1,2]','imported')",u,f.doc(),f.text(),1,f.index());
  }
  @Test void coveragePaginationProjectionsAndExternalResolution() {
    Fixture f=fixture();unit(f,true,0);unit(f,true,1);unit(f,false,2);
    var q=new LinkedMultiValueMap<String,String>();q.add("limit","1");
    var page=service.get(f.collection().toString(),null,f.doc().toString(),false,q);
    var desc=(Map<?,?>)((Map<?,?>)page.get("indexations")).get(f.index().toString());var coverage=(Map<?,?>)desc.get("coverage");
    assertEquals("partial",coverage.get("state"));assertEquals(1L,coverage.get("missing_vectors"));assertEquals(1,((List<?>)page.get("items")).size());assertNotNull(page.get("next_cursor"));
    assertFalse(((Map<?,?>)page.get("document")).containsKey("metadata"));
    q.add("cursor",page.get("next_cursor").toString());var second=service.get(f.collection().toString(),null,f.doc().toString(),false,q);assertEquals(1,((List<?>)second.get("items")).size());assertNull(second.get("next_cursor"));
    var ext=new LinkedMultiValueMap<String,String>();ext.add("external_id","doc");ext.add("include","texts,metadata");
    var selected=service.get(f.collection().toString(),f.index().toString(),null,true,ext);assertEquals(2,((List<?>)selected.get("items")).size());assertTrue(((Map<?,?>)selected.get("document")).containsKey("metadata"));assertEquals("hello",((Map<?,?>)((List<?>)selected.get("items")).getFirst()).get("text"));
  }
  @Test void emptyMissingAndUnavailable() {
    Fixture f=fixture();var q=new LinkedMultiValueMap<String,String>();var empty=service.get(f.collection().toString(),null,f.doc().toString(),false,q);
    var coverage=(Map<?,?>)((Map<?,?>)((Map<?,?>)empty.get("indexations")).get(f.index().toString())).get("coverage");assertEquals("pending",coverage.get("state"));
    jdbc.update("UPDATE dvp.indexations SET state='failed' WHERE id=?",f.index());
    assertEquals(409,assertThrows(V2Exception.class,()->service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,q)).status());
    assertEquals(List.of(f.index()),service.get(f.collection().toString(),null,f.doc().toString(),false,q).get("unavailable_indexation_ids"));
    assertEquals(404,assertThrows(V2Exception.class,()->service.get(f.collection().toString(),null,UUID.randomUUID().toString(),false,q)).status());
  }
  @Test void allFourHttpRoutesAndStaleRevision() throws Exception {
    Fixture f=fixture();unit(f,true,0);String c=f.collection().toString(),d=f.doc().toString(),i=f.index().toString();
    for(String path:List.of("/api/v2/collections/"+c+"/documents/"+d+"/vectors", "/api/v2/collections/"+c+"/indexations/"+i+"/documents/"+d+"/vectors", "/api/v2/collections/"+c+"/document-vectors?external_id=doc", "/api/v2/collections/"+c+"/indexations/"+i+"/document-vectors?external_id=doc"))
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1));
    jdbc.update("UPDATE dvp.source_texts SET revision=2 WHERE id=?",f.text());
    var result=service.get(c,i,d,false,new LinkedMultiValueMap<>());assertTrue(((List<?>)result.get("items")).isEmpty());
  }
  @Test void repeatableReadKeepsVectorAndTextInSameSnapshot() {
    Fixture f=fixture();unit(f,true,0);
    reads.execute(status->{
      readJdbc.queryForObject("SELECT revision FROM dvp.documents WHERE id=?",Long.class,f.doc());
      jdbc.update("UPDATE dvp.source_texts SET content='changed',revision=2 WHERE id=?",f.text());
      var q=new LinkedMultiValueMap<String,String>();q.add("include","texts");
      var result=service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,q);
      assertEquals("hello",((Map<?,?>)((List<?>)result.get("items")).getFirst()).get("text"));return null;
    });
    assertTrue(((List<?>)service.get(f.collection().toString(),f.index().toString(),f.doc().toString(),false,new LinkedMultiValueMap<>()).get("items")).isEmpty());
  }

  @Test void readPoolExhaustionReturns503WhileWriteAndCoordinationContinue() throws Exception {
    Fixture f=fixture();
    UUID job=UUID.randomUUID();jdbc.update("INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters) VALUES (?,?,'prepare','queued','{}')",job,f.collection());
    var lease=jobs.claim("retrieval-test-"+UUID.randomUUID(),Set.of("prepare")).orElseThrow();
    try(var held=readDataSource.getConnection()) {
      long start=System.nanoTime();
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/collections/"+f.collection()+"/documents/"+f.doc()+"/vectors")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
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
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid NOT NULL,source_revision bigint NOT NULL,indexation_id uuid NOT NULL,embedding vector(3) NOT NULL,origin varchar(10) NOT NULL)");
    jdbc.update("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash) VALUES (?,?,?,?,?,1,0,0,5,?)",unit,f.collection(),index,f.doc(),f.text(),"b".repeat(64));
    jdbc.update("INSERT INTO dvp."+table+" VALUES (?,?,?,?,?,'[1,2,3]','imported')",unit,f.doc(),f.text(),1,index);
    var q=new LinkedMultiValueMap<String,String>();var result=service.get(f.collection().toString(),null,f.doc().toString(),false,q);
    assertEquals(2,((Map<?,?>)result.get("indexations")).size());assertEquals(2,((List<?>)result.get("items")).size());
    jdbc.update("UPDATE dvp.indexations SET selection='{\"kinds\":[\"title\"]}' WHERE id=?",index);
    var changed=service.get(f.collection().toString(),index.toString(),f.doc().toString(),false,q);
    var coverage=(Map<?,?>)((Map<?,?>)((Map<?,?>)changed.get("indexations")).get(index.toString())).get("coverage");assertEquals("not_applicable",coverage.get("state"));assertTrue(((List<?>)changed.get("items")).isEmpty());
  }

}
