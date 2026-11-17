package org.lareferencia.embedding.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL", matches=".+")
class MigrationIntegrationTest {
  @Test void retiresOutstandingPreparationJobsAndPreservesHistory() throws Exception {
    String url=TestDatabase.create();
    Flyway.configure().dataSource(url,"postgres","").target("7").load().migrate();
    try(var db=DriverManager.getConnection(url,"postgres","");var sql=db.createStatement()) {
      sql.execute("""
          INSERT INTO dvp.collections(id,slug,name) VALUES ('11111111-1111-4111-8111-111111111111','migration','Migration');
          INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters,lease_owner,lease_until) VALUES
          ('22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','prepare','running','{}','worker',now()),
          ('33333333-3333-4333-8333-333333333333','11111111-1111-4111-8111-111111111111','prepare','succeeded','{}',NULL,NULL),
          ('44444444-4444-4444-8444-444444444444','11111111-1111-4111-8111-111111111111','generate','queued','{}',NULL,NULL);
          INSERT INTO dvp.job_items(job_id,item_key,status,lease_owner,lease_until)
          VALUES ('22222222-2222-4222-8222-222222222222','one','running','worker',now());
          """);
    }
    Flyway.configure().dataSource(url,"postgres","").load().migrate();
    try(var db=DriverManager.getConnection(url,"postgres","");var sql=db.createStatement()) {
      try(var jobs=sql.executeQuery("SELECT status,global_error_code,lease_owner,finished_at FROM dvp.jobs ORDER BY id")) {
        assertTrue(jobs.next());assertEquals("failed",jobs.getString(1));
        assertEquals("job_kind_unavailable",jobs.getString(2));assertNull(jobs.getString(3));assertNotNull(jobs.getTimestamp(4));
        assertTrue(jobs.next());assertEquals("succeeded",jobs.getString(1));
        assertTrue(jobs.next());assertEquals("queued",jobs.getString(1));
      }
      try(var items=sql.executeQuery("SELECT status,error_code,lease_owner FROM dvp.job_items")) {
        assertTrue(items.next());assertEquals("failed",items.getString(1));
        assertEquals("job_kind_unavailable",items.getString(2));assertNull(items.getString(3));
      }
    }
  }
  @Test void documentOwnedUnitsMigrationRemovesSourceColumns() throws Exception {
    String url=TestDatabase.create();
    Flyway.configure().dataSource(url,"postgres","").target("5").load().migrate();
    Flyway.configure().dataSource(url,"postgres","").load().migrate();
    try(var c=DriverManager.getConnection(url,"postgres","");var st=c.createStatement()) {
      var result=st.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='dvp' AND table_name='vector_units' AND column_name IN ('source_text_id','source_revision')");
      assertTrue(result.next());assertEquals(0,result.getInt(1));
      var snapshot=st.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='dvp' AND table_name='vector_units' AND column_name IN ('prepared_text','prepared_key')");
      assertTrue(snapshot.next());assertEquals(2,snapshot.getInt(1));
    }
  }
  @Test void documentOwnedMigrationPreservesExistingGeneratedVector() throws Exception {
    String url=TestDatabase.create();Flyway.configure().dataSource(url,"postgres","").target("5").load().migrate();
    String c="11111111-1111-4111-8111-111111111111",d="22222222-2222-4222-8222-222222222222",t="33333333-3333-4333-8333-333333333333",p="44444444-4444-4444-8444-444444444444",i="55555555-5555-4555-8555-555555555555",u="66666666-6666-4666-8666-666666666666",table="vec_55555555555545558555555555555555";
    try(var db=DriverManager.getConnection(url,"postgres","");var s=db.createStatement()) {
      s.execute("INSERT INTO dvp.collections(id,slug,name) VALUES ('"+c+"','migration','Migration')");
      s.execute("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES ('"+d+"','"+c+"','doc')");
      s.execute("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,content,content_hash) VALUES ('"+t+"','"+d+"','title','title','hello','"+"a".repeat(64)+"')");
      s.execute("INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint) VALUES ('"+p+"','migration','model','v1',2,'import_only','{}','"+"b".repeat(64)+"')");
      s.execute("INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state) VALUES ('"+i+"','"+c+"','migration','"+p+"','{}','{\"mode\":\"none\"}','cosine','none','"+table+"','ready','disabled')");
      s.execute("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash) VALUES ('"+u+"','"+c+"','"+i+"','"+d+"','"+t+"',1,0,0,5,'"+"c".repeat(64)+"')");
      s.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid,source_revision bigint,indexation_id uuid NOT NULL,embedding vector(2) NOT NULL,input_hash char(64),origin varchar(10) NOT NULL,FOREIGN KEY(unit_id,document_id,indexation_id) REFERENCES dvp.vector_units(id,document_id,indexation_id) ON DELETE CASCADE)");
      s.execute("INSERT INTO dvp."+table+" VALUES ('"+u+"','"+d+"','"+t+"',1,'"+i+"','[1,2]','"+"c".repeat(64)+"','generated')");
    }
    Flyway.configure().dataSource(url,"postgres","").load().migrate();
    try(var db=DriverManager.getConnection(url,"postgres","");var s=db.createStatement()) {
      var row=s.executeQuery("SELECT u.prepared_text,v.embedding::text FROM dvp.vector_units u JOIN dvp."+table+" v ON v.unit_id=u.id WHERE u.id='"+u+"'");
      assertTrue(row.next());assertEquals("hello",row.getString(1));assertEquals("[1,2]",row.getString(2));
      s.execute("DELETE FROM dvp.source_texts WHERE id='"+t+"'");
      var count=s.executeQuery("SELECT count(*) FROM dvp."+table);assertTrue(count.next());assertEquals(1,count.getInt(1));
    }
  }
  @Test void legacyInstallationRequiresExplicitBaselineAndKeepsRowsAndVectors() throws Exception {
    String url = TestDatabase.create();
    try (var c = DriverManager.getConnection(url,"postgres",""); var s = c.createStatement()) {
      s.execute("CREATE TABLE stores (id uuid primary key, name varchar(60) not null, model varchar(60) not null, provider varchar(20) not null, embedding_dimension integer, created_at timestamptz not null)");
      s.execute("INSERT INTO stores VALUES ('11111111-1111-1111-1111-111111111111','legacy','model','ollama',3,now())");
      s.execute("CREATE TABLE embeddings_legacy (id text primary key, embedding vector(3))");
      s.execute("INSERT INTO embeddings_legacy VALUES ('hash','[1,2,3]')");
    }
    assertThrows(Exception.class, () -> Flyway.configure().dataSource(url,"postgres","").load().migrate());
    var flyway = Flyway.configure().dataSource(url,"postgres","").baselineVersion("1").load();
    flyway.baseline();
    flyway.migrate();
    assertEquals(0, flyway.migrate().migrationsExecuted);
    try (var c = DriverManager.getConnection(url,"postgres",""); var s = c.createStatement()) {
      var r = s.executeQuery("SELECT name, embedding::text FROM stores CROSS JOIN embeddings_legacy");
      assertTrue(r.next()); assertEquals("legacy",r.getString(1)); assertEquals("[1,2,3]",r.getString(2));
    }
  }

  @Test void duplicatePreflightFailsWithoutChangingLegacyRows() throws Exception {
    String url = TestDatabase.create();
    var flyway = Flyway.configure().dataSource(url,"postgres","").target("1").load();
    flyway.migrate();
    try (var c = DriverManager.getConnection(url,"postgres",""); var s = c.createStatement()) {
      s.execute("INSERT INTO stores VALUES (gen_random_uuid(),'Name','model1','ollama',3,now()),(gen_random_uuid(),'NAME','model2','ollama',3,now())");
    }
    assertThrows(Exception.class, () -> Flyway.configure().dataSource(url,"postgres","").load().migrate());
    try (var c = DriverManager.getConnection(url,"postgres",""); var s = c.createStatement()) {
      var r = s.executeQuery("SELECT count(*) FROM stores"); r.next(); assertEquals(2,r.getInt(1));
      var i = s.executeQuery("SELECT to_regclass('uk_store_name_ci')"); i.next(); assertNull(i.getString(1));
    }
  }

  @Test void failedAdditiveMigrationRollsBackItsDdlAndPreservesApprovedSchema(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path scripts) throws Exception {
    String url=TestDatabase.create();
    Flyway.configure().dataSource(url,"postgres","").load().migrate();
    java.nio.file.Files.writeString(scripts.resolve("V4__injected_failure.sql"),
        "CREATE TABLE dvp.must_rollback(id integer); SELECT 1/0;");
    assertThrows(Exception.class,()->Flyway.configure().dataSource(url,"postgres","")
        .locations("classpath:db/migration","filesystem:"+scripts).load().migrate());
    try(var c=DriverManager.getConnection(url,"postgres","");var st=c.createStatement()) {
      var r=st.executeQuery("SELECT to_regclass('dvp.must_rollback'),to_regclass('dvp.collections'),to_regclass('stores')");
      assertTrue(r.next());assertNull(r.getString(1));assertNotNull(r.getString(2));assertNotNull(r.getString(3));
    }
    assertEquals(0,Flyway.configure().dataSource(url,"postgres","").load().migrate().migrationsExecuted);
  }
  @Test void upgradeToDocumentVectorsPreservesExistingTextVectors() throws Exception {
    String url=TestDatabase.create();Flyway.configure().dataSource(url,"postgres","").target("4").load().migrate();
    String collection="11111111-1111-4111-8111-111111111111",document="22222222-2222-4222-8222-222222222222",source="33333333-3333-4333-8333-333333333333",profile="44444444-4444-4444-8444-444444444444",index="55555555-5555-4555-8555-555555555555",unit="66666666-6666-4666-8666-666666666666",newUnit="77777777-7777-4777-8777-777777777777",table="vec_55555555555545558555555555555555";
    try(var c=DriverManager.getConnection(url,"postgres","");var st=c.createStatement()) {
      st.execute("INSERT INTO dvp.collections(id,slug,name) VALUES ('"+collection+"','upgrade','upgrade')");
      st.execute("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES ('"+document+"','"+collection+"','doc')");
      st.execute("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,content,content_hash) VALUES ('"+source+"','"+document+"','title','title','text','"+"a".repeat(64)+"')");
      st.execute("INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint) VALUES ('"+profile+"','upgrade','m','v1',2,'import_only','{}','"+"b".repeat(64)+"')");
      st.execute("INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state) VALUES ('"+index+"','"+collection+"','upgrade','"+profile+"','{}','{\"mode\":\"none\"}','cosine','none','"+table+"','ready','disabled')");
      st.execute("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash) VALUES ('"+unit+"','"+collection+"','"+index+"','"+document+"','"+source+"',1,0,0,4,'"+"c".repeat(64)+"')");
      st.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid NOT NULL,source_revision bigint NOT NULL,indexation_id uuid NOT NULL,embedding vector(2) NOT NULL,input_hash char(64) NOT NULL,origin varchar(10) NOT NULL,FOREIGN KEY (unit_id,document_id,source_text_id,source_revision,indexation_id) REFERENCES dvp.vector_units(id,document_id,source_text_id,source_revision,indexation_id) ON DELETE CASCADE)");
      st.execute("INSERT INTO dvp."+table+" VALUES ('"+unit+"','"+document+"','"+source+"',1,'"+index+"','[1,2]','"+"c".repeat(64)+"','imported')");
    }
    Flyway.configure().dataSource(url,"postgres","").target("5").load().migrate();
    try(var c=DriverManager.getConnection(url,"postgres","");var st=c.createStatement()) {
      try(var old=st.executeQuery("SELECT embedding::text FROM dvp."+table+" WHERE unit_id='"+unit+"'")){assertTrue(old.next());assertEquals("[1,2]",old.getString(1));}
      st.execute("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,ordinal,embedding_key) VALUES ('"+newUnit+"','"+collection+"','"+index+"','"+document+"',0,'external-vector')");
      st.execute("INSERT INTO dvp."+table+" (unit_id,document_id,indexation_id,embedding,origin) VALUES ('"+newUnit+"','"+document+"','"+index+"','[2,3]','imported')");
      try(var count=st.executeQuery("SELECT count(*) FROM dvp."+table)){assertTrue(count.next());assertEquals(2,count.getInt(1));}
    }
  }

}
