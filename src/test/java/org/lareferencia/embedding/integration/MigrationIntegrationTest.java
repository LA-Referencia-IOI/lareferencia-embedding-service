package org.lareferencia.embedding.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL", matches=".+")
class MigrationIntegrationTest {
  @Test void freshDatabaseRunsBothMigrationsAndCanRestart() throws Exception {
    String url = TestDatabase.create();
    var flyway = Flyway.configure().dataSource(url,"postgres","").load();
    assertEquals(2, flyway.migrate().migrationsExecuted);
    assertEquals(0, flyway.migrate().migrationsExecuted);
    try (var c = DriverManager.getConnection(url,"postgres",""); var sql = c.createStatement();
         var row = sql.executeQuery("""
             SELECT to_regclass('public.stores'), to_regclass('dvp.vector_units'),
                    to_regclass('dvp.text_indexation_progress'), to_regclass('dvp.job_items_due')
             """)) {
      assertTrue(row.next());
      for (int column=1; column<=4; column++) assertNotNull(row.getString(column));
    }
  }

  @Test void legacyUpgradePreservesStoresAndVectors() throws Exception {
    String url = legacyDatabase();
    assertThrows(FlywayException.class, () -> Flyway.configure().dataSource(url,"postgres","").load().migrate());
    var upgrade = legacyUpgrade(url);
    assertEquals(2, upgrade.migrate().migrationsExecuted);
    assertEquals(0, Flyway.configure().dataSource(url,"postgres","").load().migrate().migrationsExecuted);
    try (var c = DriverManager.getConnection(url,"postgres",""); var sql = c.createStatement();
         var row = sql.executeQuery("""
             SELECT name, embedding::text, to_regclass('dvp.documents'),
                    to_regclass('public.uk_store_name_ci')
             FROM public.stores CROSS JOIN public.embeddings_legacy
             """)) {
      assertTrue(row.next());
      assertEquals("Legacy", row.getString(1));
      assertEquals("[1,2,3]", row.getString(2));
      assertNotNull(row.getString(3));
      assertNotNull(row.getString(4));
    }
  }

  @Test void duplicateLegacyStoresAbortBeforeCreatingV2Schema() throws Exception {
    String url = legacyDatabase();
    try (var c = DriverManager.getConnection(url,"postgres",""); var sql = c.createStatement()) {
      sql.execute("INSERT INTO public.stores VALUES (gen_random_uuid(),'LEGACY','other','ollama',3,now())");
    }
    var error = assertThrows(FlywayException.class, () -> legacyUpgrade(url).migrate());
    assertTrue(error.getMessage().contains("Legacy store duplicates found"));
    try (var c = DriverManager.getConnection(url,"postgres",""); var sql = c.createStatement();
         var row = sql.executeQuery("SELECT count(*), to_regnamespace('dvp'), to_regclass('public.uk_store_name_ci') FROM public.stores")) {
      assertTrue(row.next());
      assertEquals(2, row.getInt(1));
      assertNull(row.getString(2));
      assertNull(row.getString(3));
    }
  }

  @Test void previousV2HistoryIsRejected(@TempDir java.nio.file.Path scripts) throws Exception {
    String url = TestDatabase.create();
    java.nio.file.Files.writeString(scripts.resolve("V1__legacy_stores.sql"), "CREATE TABLE old_registry(id integer);");
    Flyway.configure().dataSource(url,"postgres","").locations("filesystem:"+scripts).load().migrate();
    assertThrows(FlywayException.class, () -> Flyway.configure().dataSource(url,"postgres","").load().migrate());
  }

  private static Flyway legacyUpgrade(String url) {
    return Flyway.configure().dataSource(url,"postgres","").baselineOnMigrate(true).baselineVersion("0").load();
  }

  private static String legacyDatabase() throws Exception {
    String url = TestDatabase.create();
    try (var c = DriverManager.getConnection(url,"postgres",""); var sql = c.createStatement()) {
      sql.execute("""
          CREATE TABLE public.stores (
              id uuid PRIMARY KEY, name varchar(60) NOT NULL UNIQUE, model varchar(60) NOT NULL,
              provider varchar(20) NOT NULL, embedding_dimension integer, created_at timestamptz NOT NULL,
              UNIQUE (model, embedding_dimension)
          );
          INSERT INTO public.stores VALUES (gen_random_uuid(),'Legacy','model','ollama',3,now());
          CREATE TABLE public.embeddings_legacy (id text PRIMARY KEY, embedding vector(3));
          INSERT INTO public.embeddings_legacy VALUES ('hash','[1,2,3]');
          """);
    }
    return url;
  }
}
