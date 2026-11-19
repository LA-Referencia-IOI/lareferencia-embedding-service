package org.lareferencia.embedding.v2.importing;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.document.*;
import org.lareferencia.embedding.v2.retrieval.VectorRetrievalService;
import org.lareferencia.embedding.v2.search.SearchService;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;

@EnabledIfEnvironmentVariable(named = "DVP_TEST_DB_URL", matches = ".+")
@SpringBootTest(
    properties = {
      "spring.config.location=classpath:/application.properties",
      "app.dvp.enabled=true",
      "app.dvp.workers-enabled=false"
    })
class InlineDocumentImportIntegrationTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    String url = TestDatabase.create();
    r.add("spring.datasource.url", () -> url);
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "");
  }

  @Autowired ImportRecordWriter writer;
  @Autowired DocumentService documents;
  @Autowired VectorRetrievalService retrieval;
  @Autowired SearchService search;
  @Autowired ObjectMapper mapper;
  @Autowired org.lareferencia.embedding.v2.job.JobRepository jobs;
  @Autowired JsonlImportProcessor jsonl;
  @Autowired JsonImportProcessor json;

  @Autowired
  @Qualifier("jdbcTemplate")
  JdbcTemplate jdbc;

  @Autowired
  @Qualifier("writeTransactions")
  TransactionTemplate writes;

  @org.junit.jupiter.api.AfterEach
  void stopFixtureJobs() {
    jdbc.update(
        "UPDATE dvp.jobs SET status='failed',lease_owner=NULL,lease_until=NULL WHERE status IN ('queued','running')");
  }

  private record Fixture(UUID collection, UUID job, String profile) {}

  private Fixture fixture() {
    UUID c = UUID.randomUUID(), job = UUID.randomUUID();
    String profile = "inline-" + UUID.randomUUID();
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)", c, "c-" + c, "test");
    jdbc.update(
        "INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters) VALUES (?,?,'import','queued','{}')", job, c);
    return new Fixture(c, job, profile);
  }

  private ObjectNode record(Fixture f, String external) throws Exception {
    return (ObjectNode)
        mapper.readTree(
            "{\"external_id\":\""
                + external
                + "\",\"metadata\":{\"year\":2025},\"embedding_profile\":{\"slug\":\""
                + f.profile()
                + "\",\"model\":\""
                + f.profile()
                + "\",\"model_revision\":\"v1\",\"dimensions\":2,\"execution_mode\":\"import_only\",\"preparation\":{\"version\":\"identity-v1\"}},\"indexation\":{\"slug\":\""
                + f.profile()
                + "\",\"metric\":\"cosine\",\"ann_strategy\":\"none\",\"chunking\":{\"mode\":\"none\"}},\"texts\":[{\"knd\":\"title\",\"lng\":\"en\",\"cnt\":\"Open science\"}],\"embeddings\":[{\"id\":\"document-embedding-1\",\"vec\":[1,2]},{\"id\":\"independent-embedding-2\",\"vec\":[2,3]}]}");
  }

  private ImportRecordWriter.Result apply(Fixture f, JsonNode input) {
    return writes.execute(tx -> writer.apply(f.collection(), f.job(), input, "merge"));
  }

  @Test
  void sharedIndexationImportsAndSearchesAcrossCollections() throws Exception {
    Fixture first = fixture(), second = fixture();
    var original = record(first, "same-id");
    UUID firstDoc = apply(first, original).documentId();
    UUID index =
        jdbc.queryForObject(
            "SELECT id FROM dvp.indexations WHERE id IN (SELECT indexation_id FROM dvp.collection_indexations WHERE collection_id=?)",
            UUID.class,
            first.collection());
    var shared = original.deepCopy();
    UUID secondDoc = apply(second, shared).documentId();
    assertNotEquals(firstDoc, secondDoc);
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.collection_indexations WHERE indexation_id=?", Integer.class, index));
    var query = mapper.readTree("{\"query\":{\"vector\":[1,2]},\"mode\":\"exact\",\"result_unit\":\"document\"}");
    assertEquals(2, ((List<?>) search.search(null, index.toString(), query).get("results")).size());
    assertEquals(
        1, ((List<?>) search.search(second.collection().toString(), index.toString(), query).get("results")).size());
    var params = new LinkedMultiValueMap<String, String>();
    params.add("external_id", "same-id");
    assertEquals(
        "ambiguous_external_id",
        assertThrows(V2Exception.class, () -> retrieval.get(null, index.toString(), null, true, params)).code());
    params.add("collection_id", second.collection().toString());
    assertEquals(2, ((List<?>) retrieval.get(null, index.toString(), null, true, params).get("data")).size());
    var profileAlias = record(first, "profile-alias");
    ((ObjectNode) profileAlias.get("embedding_profile")).put("slug", "alias-" + UUID.randomUUID());
    apply(second, profileAlias);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.embedding_profiles WHERE model=?", Integer.class, first.profile()));
    var differentIndex = record(first, "new-index");
    ((ObjectNode) differentIndex.get("indexation")).put("slug", "new-" + UUID.randomUUID());
    apply(second, differentIndex);
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.indexations WHERE profile_id=(SELECT profile_id FROM dvp.indexations WHERE id=?)",
            Integer.class,
            index));
    var conflict = record(first, "conflict");
    String newProfile = "new-" + UUID.randomUUID();
    ((ObjectNode) conflict.get("embedding_profile")).put("slug", newProfile).put("model", newProfile);
    assertEquals("indexation_conflict", assertThrows(V2Exception.class, () -> apply(second, conflict)).code());
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM dvp.embedding_profiles WHERE slug=?", Integer.class, newProfile));
    ((ObjectNode) conflict.get("indexation")).put("slug", "new-" + UUID.randomUUID());
    apply(second, conflict);
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM dvp.embedding_profiles WHERE slug=?", Integer.class, newProfile));
  }

  @Test
  void createsResourcesStoresIndependentEmbeddingsAndPreservesThemAcrossEdits() throws Exception {
    Fixture f = fixture();
    var data = record(f, "document-a");
    UUID doc = apply(f, data).documentId();
    UUID index =
        jdbc.queryForObject(
            "SELECT id FROM dvp.indexations WHERE id IN (SELECT indexation_id FROM dvp.collection_indexations WHERE collection_id=?) AND slug=?",
            UUID.class,
            f.collection(),
            f.profile());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.vector_units WHERE document_id=? AND embedding_key IS NOT NULL",
            Integer.class,
            doc));
    var page =
        retrieval.get(f.collection().toString(), index.toString(), doc.toString(), false, new LinkedMultiValueMap<>());
    var items = (List<?>) page.get("data");
    assertEquals(2, items.size());
    assertEquals(
        Set.of("document-embedding-1", "independent-embedding-2"),
        new HashSet<>(
            jdbc.queryForList("SELECT embedding_key FROM dvp.vector_units WHERE document_id=?", String.class, doc)));
    var query =
        mapper.readTree("{\"query\":{\"vector\":[1,2]},\"mode\":\"exact\",\"result_unit\":\"unit\",\"top_n\":10}");
    assertEquals(
        2, ((List<?>) search.search(f.collection().toString(), index.toString(), query).get("results")).size());
    String textKey =
        jdbc.queryForObject("SELECT text_key FROM dvp.source_texts WHERE document_id=?", String.class, doc);
    var changed = new DocumentWriteRequest();
    changed.setTexts(List.of(new SourceTextInput(textKey, "title", "en", "Changed source")));
    changed.setMetadata(mapper.readTree("{\"year\":2026}"));
    writes.executeWithoutResult(
        tx -> documents.upsertInTransaction(f.collection(), "document-a", changed, false, null));
    assertEquals(
        2,
        ((List<?>)
                retrieval
                    .get(
                        f.collection().toString(), index.toString(), doc.toString(), false, new LinkedMultiValueMap<>())
                    .get("data"))
            .size());
    assertEquals(
        2, ((List<?>) search.search(f.collection().toString(), index.toString(), query).get("results")).size());
    apply(f, record(f, "document-b"));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM dvp.embedding_profiles WHERE slug=?", Integer.class, f.profile()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.indexations WHERE id IN (SELECT indexation_id FROM dvp.collection_indexations WHERE collection_id=?)",
            Integer.class,
            f.collection()));
  }

  @Test
  void conflictsAndInvalidVectorsRollBackTheWholeRecord() throws Exception {
    Fixture f = fixture();
    var data = record(f, "first");
    apply(f, data);
    var conflicting = record(f, "conflicting");
    ((ObjectNode) conflicting.path("embedding_profile")).put("model", "different");
    assertEquals("profile_conflict", assertThrows(V2Exception.class, () -> apply(f, conflicting)).code());
    var wrongIndex = record(f, "wrong-index");
    ((ObjectNode) wrongIndex.path("indexation")).put("metric", "l2");
    assertEquals("indexation_conflict", assertThrows(V2Exception.class, () -> apply(f, wrongIndex)).code());
    var invalid = record(f, "invalid");
    ((ObjectNode) invalid.path("embeddings").get(1)).set("vec", mapper.readTree("[0,0]"));
    assertEquals("invalid_vector", assertThrows(V2Exception.class, () -> apply(f, invalid)).code());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.documents WHERE collection_id=? AND external_id IN ('conflicting','wrong-index','invalid')",
            Integer.class,
            f.collection()));
  }

  @Test
  void twoRecordImportsReuseResourcesAndReplaceOnlyMatchingEmbeddingIds() throws Exception {
    Fixture f = fixture();
    var first = record(f, "same-document");
    var result = apply(f, first);
    var updated = record(f, "same-document");
    ((ObjectNode) updated.path("embeddings").get(0)).set("vec", mapper.readTree("[4,5]"));
    apply(f, updated);
    UUID index =
        jdbc.queryForObject(
            "SELECT id FROM dvp.indexations WHERE id IN (SELECT indexation_id FROM dvp.collection_indexations WHERE collection_id=?)",
            UUID.class,
            f.collection());
    String table = "vec_" + index.toString().replace("-", "");
    assertEquals(
        "[4,5]",
        jdbc.queryForObject(
            "SELECT v.embedding::text FROM dvp."
                + table
                + " v JOIN dvp.vector_units u ON u.id=v.unit_id WHERE u.document_id=? AND u.embedding_key='document-embedding-1'",
            String.class,
            result.documentId()));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.vector_units WHERE document_id=? AND embedding_key IS NOT NULL",
            Integer.class,
            result.documentId()));
  }

  @Test
  void newResourcesAreRolledBackWhenARecordFails() throws Exception {
    Fixture f = fixture();
    var invalid = record(f, "broken");
    ((ObjectNode) invalid.path("embeddings").get(1)).set("vec", mapper.readTree("[0,0]"));
    assertThrows(V2Exception.class, () -> apply(f, invalid));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM dvp.embedding_profiles WHERE slug=?", Integer.class, f.profile()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.indexations WHERE id IN (SELECT indexation_id FROM dvp.collection_indexations WHERE collection_id=?)",
            Integer.class,
            f.collection()));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?", Integer.class, f.collection()));
  }

  @Test
  void jsonlAndJsonJobsAcceptInlineDocuments() throws Exception {
    for (String format : List.of("jsonl", "json")) {
      Fixture f = fixture();
      ObjectNode data = record(f, "inline-" + format);
      byte[] bytes;
      if (format.equals("jsonl")) {
        data.put("schema_version", "1");
        bytes = (mapper.writeValueAsString(data) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      } else
        bytes =
            ("{\"schema_version\":\"1\",\"documents\":[" + mapper.writeValueAsString(data) + "]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      jdbc.update(
          "UPDATE dvp.jobs SET parameters=?::jsonb WHERE id=?",
          "{\"format\":\"" + format + "\",\"mode\":\"merge\"}",
          f.job());
      var lease = jobs.claim("inline-" + UUID.randomUUID(), Set.of("import")).orElseThrow();
      assertEquals(f.job(), lease.jobId());
      int processed =
          format.equals("jsonl")
              ? jsonl.process(lease, new java.io.ByteArrayInputStream(bytes))
              : json.process(lease, () -> new java.io.ByteArrayInputStream(bytes));
      assertEquals(1, processed);
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT count(*) FROM dvp.vector_units WHERE collection_id=? AND embedding_key IS NOT NULL",
              Integer.class,
              f.collection()));
      jdbc.update("UPDATE dvp.jobs SET status='failed',lease_owner=NULL,lease_until=NULL WHERE id=?", f.job());
    }
  }

  @Test
  void approximateSearchIncludesDocumentLevelEmbeddings() throws Exception {
    Fixture f = fixture();
    var data = record(f, "ann-document");
    ((ObjectNode) data.path("indexation")).put("ann_strategy", "hnsw_vector");
    UUID doc = apply(f, data).documentId();
    UUID index =
        jdbc.queryForObject(
            "SELECT id FROM dvp.indexations WHERE id IN (SELECT indexation_id FROM dvp.collection_indexations WHERE collection_id=?)",
            UUID.class,
            f.collection());
    String table = "vec_" + index.toString().replace("-", "");
    jdbc.execute("CREATE INDEX " + table + "_ann ON dvp." + table + " USING hnsw (embedding vector_cosine_ops)");
    jdbc.update("UPDATE dvp.indexations SET ann_state='ready' WHERE id=?", index);
    var request =
        mapper.readTree(
            "{\"query\":{\"vector\":[1,2]},\"include\":[\"texts\",\"metadata\"],\"result_unit\":\"unit\",\"top_n\":10}");
    var response = search.search(f.collection().toString(), index.toString(), request);
    var found = (List<?>) response.get("results");
    assertEquals(2, found.size());
    assertEquals(doc, ((Map<?, ?>) found.getFirst()).get("document_id"));
    assertTrue(((Map<?, ?>) found.getFirst()).containsKey("id"));
    assertFalse(((Map<?, ?>) found.getFirst()).containsKey("text_id"));
  }

  @Test
  void concurrentImportsCreateOnlyOneProfileAndIndexation() throws Exception {
    Fixture f = fixture();
    var one = record(f, "parallel-a");
    var two = record(f, "parallel-b");
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var a =
          executor.submit(() -> {
            barrier.await();
            return apply(f, one);
          });
      var b =
          executor.submit(() -> {
            barrier.await();
            return apply(f, two);
          });
      assertNotEquals(
          a.get(15, java.util.concurrent.TimeUnit.SECONDS).documentId(),
          b.get(15, java.util.concurrent.TimeUnit.SECONDS).documentId());
    }
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM dvp.embedding_profiles WHERE slug=?", Integer.class, f.profile()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.indexations WHERE id IN (SELECT indexation_id FROM dvp.collection_indexations WHERE collection_id=?) AND slug=?",
            Integer.class,
            f.collection(),
            f.profile()));
    assertEquals(
        4,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.vector_units WHERE collection_id=? AND embedding_key IS NOT NULL",
            Integer.class,
            f.collection()));
  }

  @Test
  void paginationAndCoverageHandleTextAndDocumentVectorsTogether() throws Exception {
    Fixture f = fixture();
    UUID doc = apply(f, record(f, "mixed")).documentId();
    UUID index =
        jdbc.queryForObject(
            "SELECT id FROM dvp.indexations WHERE id IN (SELECT indexation_id FROM dvp.collection_indexations WHERE collection_id=?)",
            UUID.class,
            f.collection());
    String table = "vec_" + index.toString().replace("-", "");
    UUID unit = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,ordinal,start_cp,end_cp,input_hash,prepared_text,prepared_key) VALUES (?,?,?,?,0,0,12,?,'Open science',?)",
        unit,
        f.collection(),
        index,
        doc,
        "a".repeat(64),
        unit.toString());
    jdbc.update(
        "INSERT INTO dvp."
            + table
            + " (unit_id,document_id,indexation_id,embedding,input_hash,origin) VALUES (?,?,?,?::vector,?,'generated')",
        unit,
        doc,
        index,
        "[1,2]",
        "a".repeat(64));
    var q = new LinkedMultiValueMap<String, String>();
    q.add("limit", "1");
    int count = 0;
    do {
      var page = retrieval.get(f.collection().toString(), index.toString(), doc.toString(), false, q);
      count += ((List<?>) page.get("data")).size();
      q.remove("cursor");
      if (page.get("next_cursor") != null) q.add("cursor", page.get("next_cursor").toString());
      else break;
    } while (true);
    assertEquals(3, count);
  }

  @Test
  void documentsOnlyJsonlExampleImportsAndReimportsWithoutProfiles() throws Exception {
    Fixture f = fixture();
    byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("docs/examples/import-documents.jsonl"));
    for (int run = 0; run < 2; run++) {
      UUID job = run == 0 ? f.job() : UUID.randomUUID();
      if (run > 0)
        jdbc.update(
            "INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters) VALUES (?,?,'import','queued','{}')",
            job,
            f.collection());
      jdbc.update("UPDATE dvp.jobs SET parameters='{\"format\":\"jsonl\",\"mode\":\"merge\"}'::jsonb WHERE id=?", job);
      var lease = jobs.claim("compact-documents-" + UUID.randomUUID(), Set.of("import")).orElseThrow();
      assertEquals(job, lease.jobId());
      assertEquals(3, jsonl.process(lease, new java.io.ByteArrayInputStream(bytes)));
      assertEquals(0, jsonl.process(lease, new java.io.ByteArrayInputStream(bytes)));
      assertTrue(jobs.finishIfComplete(lease));
    }
    assertEquals(
        3,
        jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?", Integer.class, f.collection()));
    assertEquals(
        6,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.source_texts s JOIN dvp.documents d ON d.id=s.document_id WHERE d.collection_id=?",
            Integer.class,
            f.collection()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.indexations WHERE id IN (SELECT indexation_id FROM dvp.collection_indexations WHERE collection_id=?)",
            Integer.class,
            f.collection()));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM dvp.documents WHERE collection_id=? AND revision<>1", Integer.class, f.collection()));
  }

  @Test
  void compactTextCannotBeMixedWithLegacyTextLinkedVectors() throws Exception {
    Fixture f = fixture();
    var input =
        mapper.readTree(
            "{\"external_id\":\"mixed\",\"texts\":[{\"knd\":\"title\",\"cnt\":\"Open science\"}],\"embeddings\":[{\"text_id\":\"title\"}]}");
    assertEquals("invalid_request", assertThrows(V2Exception.class, () -> apply(f, input)).code());
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?", Integer.class, f.collection()));
  }
}
