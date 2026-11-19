package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pgvector.PGvector;
import java.util.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.document.*;
import org.lareferencia.embedding.v2.indexation.IndexationService;
import org.lareferencia.embedding.v2.profile.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Applies one complete import record in the caller's fenced write transaction. */
@Component
@ConditionalOnProperty(name = "app.dvp.enabled", havingValue = "true")
public class ImportRecordWriter {
  public record Result(UUID documentId, int vectorsInserted) {}

  private final DocumentService documents;
  private final ImportVectorValidator vectors;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final ProfileService profiles;
  private final IndexationService indexations;
  private final DocumentValidation validation;

  public ImportRecordWriter(
      DocumentService documents,
      ImportVectorValidator vectors,
      @Qualifier("jdbcTemplate") JdbcTemplate jdbc,
      ObjectMapper mapper,
      ProfileService profiles,
      IndexationService indexations,
      DocumentValidation validation) {
    this.documents = documents;
    this.vectors = vectors;
    this.jdbc = jdbc;
    this.mapper =
        mapper
            .copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    this.mapper
        .coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    this.profiles = profiles;
    this.indexations = indexations;
    this.validation = validation;
  }

  public Result apply(UUID collection, UUID job, JsonNode record, String mode) {
    try {
      return applyRecord(collection, job, record, mode);
    } catch (org.springframework.dao.DataAccessException e) {
      // Database diagnostics can contain the submitted source or metadata.
      throw new V2Exception(503, "database_unavailable", "Database operation unavailable");
    }
  }

  private Result applyRecord(UUID collection, UUID job, JsonNode record, String mode) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("An active write transaction is required");
    Objects.requireNonNull(collection);
    Objects.requireNonNull(job);
    if (!Set.of("merge", "replace").contains(mode)) throw V2Exception.badRequest("Invalid import mode");
    if (record != null && record.isObject() && (record.has("embedding_profile") || record.has("indexation")))
      return applyInline(collection, job, record, mode);
    object(record, Set.of("schema_version", "external_id", "metadata", "texts", "embeddings", "expected_revision"));
    if (record.has("schema_version") && !"1".equals(text(record, "schema_version", true)))
      throw V2Exception.badRequest("Unsupported schema_version");
    String external = text(record, "external_id", true);
    Long expected = expectedRevision(record);
    ObjectNode requestNode = mapper.createObjectNode();
    if (record.has("metadata")) requestNode.set("metadata", record.get("metadata"));
    if (record.has("texts")) requestNode.set("texts", record.get("texts"));
    if (record.hasNonNull("embeddings") && (!record.get("embeddings").isArray() || !record.get("embeddings").isEmpty()))
      throw V2Exception.badRequest("Embedding profiles and indexations are required for document embeddings");
    DocumentWriteRequest request;
    try {
      request = mapper.treeToValue(requestNode, DocumentWriteRequest.class);
    } catch (java.io.IOException e) {
      throw V2Exception.badRequest("Invalid document fields");
    }
    var applied = documents.upsertInTransaction(collection, external, request, mode.equals("replace"), expected);
    return new Result(UUID.fromString(applied.body().path("id").asText()), 0);
  }

  /** Inline profile/indexation and document-level vectors use the same fenced record transaction. */
  private Result applyInline(UUID collection, UUID job, JsonNode record, String mode) {
    object(
        record,
        Set.of(
            "schema_version",
            "external_id",
            "metadata",
            "texts",
            "embeddings",
            "expected_revision",
            "embedding_profile",
            "indexation"));
    if (record.has("schema_version") && !"1".equals(text(record, "schema_version", true)))
      throw V2Exception.badRequest("Unsupported schema_version");
    ProfileRequest requestProfile;
    try {
      requestProfile = mapper.treeToValue(record.get("embedding_profile"), ProfileRequest.class);
    } catch (java.io.IOException | IllegalArgumentException e) {
      throw V2Exception.badRequest("Invalid embedding profile");
    }
    ProfileResource profile = profiles.resolveForImport(requestProfile);
    UUID index = indexations.resolveForImport(collection, profile.id(), profile.dimensions(), record.get("indexation"));
    var configs =
        jdbc.query(
            "SELECT vector_table,metric,ann_strategy FROM dvp.indexations WHERE id=? AND state='ready' AND EXISTS (SELECT 1 FROM dvp.collection_indexations ci WHERE ci.indexation_id=dvp.indexations.id AND ci.collection_id=?)",
            (rs, n) -> new InlineIndex(rs.getString(1), rs.getString(2), rs.getString(3)),
            index,
            collection);
    if (configs.size() != 1 || !("vec_" + index.toString().replace("-", "")).equals(configs.getFirst().table()))
      throw new V2Exception(409, "indexation_unavailable", "Index configuration is not ready");
    var config = configs.getFirst();
    ObjectNode document = mapper.createObjectNode();
    if (record.has("metadata")) document.set("metadata", record.get("metadata"));
    if (record.has("texts")) document.set("texts", record.get("texts"));
    DocumentWriteRequest write;
    try {
      write = mapper.treeToValue(document, DocumentWriteRequest.class);
    } catch (java.io.IOException e) {
      throw V2Exception.badRequest("Invalid document fields");
    }
    UUID doc =
        UUID.fromString(
            documents
                .upsertInTransaction(
                    collection,
                    text(record, "external_id", true),
                    write,
                    mode.equals("replace"),
                    expectedRevision(record))
                .body()
                .path("id")
                .asText());
    JsonNode supplied = record.get("embeddings");
    if (supplied == null) return new Result(doc, 0);
    if (!supplied.isArray()) throw V2Exception.badRequest("embeddings must be an array");
    Set<String> seen = new HashSet<>();
    int inserted = 0;
    for (JsonNode item : supplied) {
      object(item, Set.of("id", "vec"));
      String key = text(item, "id", true);
      validation.embeddingId(key);
      if (!seen.add(key)) throw new V2Exception(422, "duplicate_vector", "Duplicate embedding ID");
      float[] values = vectors.validate(item.get("vec"), profile.dimensions(), config.metric(), config.strategy());
      UUID proposed = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,ordinal,embedding_key) VALUES (?,?,?,?,0,?) ON CONFLICT (indexation_id,document_id,embedding_key) DO NOTHING",
          proposed,
          collection,
          index,
          doc,
          key);
      UUID unit =
          jdbc.queryForObject(
              "SELECT id FROM dvp.vector_units WHERE indexation_id=? AND document_id=? AND embedding_key=?",
              UUID.class,
              index,
              doc,
              key);
      inserted +=
          jdbc.update(
              "INSERT INTO dvp."
                  + config.table()
                  + " (unit_id,document_id,indexation_id,embedding,origin,origin_job_id) VALUES (?,?,?,?::vector,'imported',?) ON CONFLICT (unit_id) DO UPDATE SET embedding=EXCLUDED.embedding,origin='imported',origin_job_id=EXCLUDED.origin_job_id,created_at=clock_timestamp()",
              unit,
              doc,
              index,
              new PGvector(values).getValue(),
              job);
    }
    return new Result(doc, inserted);
  }

  private record InlineIndex(String table, String metric, String strategy) {}

  private static void object(JsonNode value, Set<String> fields) {
    if (value == null || !value.isObject()) throw V2Exception.badRequest("Expected object");
    value
        .fieldNames()
        .forEachRemaining(name -> {
          if (!fields.contains(name)) throw V2Exception.badRequest("Unknown field");
        });
  }

  private static String text(JsonNode value, String field, boolean required) {
    JsonNode node = value.get(field);
    if (node == null && !required) return null;
    if (node == null || !node.isTextual()) throw V2Exception.badRequest("Invalid " + field);
    return node.textValue();
  }

  private static Long expectedRevision(JsonNode record) {
    JsonNode value = record.get("expected_revision");
    if (value == null) return null;
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1)
      throw V2Exception.badRequest("Invalid expected_revision");
    return value.longValue();
  }
}
