package org.lareferencia.embedding.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL", matches=".+")
class MigrationIntegrationTest {
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
}
