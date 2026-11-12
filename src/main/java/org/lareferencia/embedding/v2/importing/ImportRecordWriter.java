package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pgvector.PGvector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.document.*;
import org.lareferencia.embedding.v2.profile.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Applies one complete import record in the caller's fenced write transaction. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class ImportRecordWriter {
  public record Result(UUID documentId,int vectorsInserted) { }
  private final DocumentService documents;private final ImportVectorValidator vectors;private final ProfilePreparation preparation;
  private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final DvpProperties properties;
  public ImportRecordWriter(DocumentService documents,ImportVectorValidator vectors,ProfilePreparation preparation,
      @Qualifier("jdbcTemplate") JdbcTemplate jdbc,ObjectMapper mapper,DvpProperties properties) {
    this.documents=documents;this.vectors=vectors;this.preparation=preparation;this.jdbc=jdbc;
    this.mapper=mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    this.mapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    this.properties=properties;
  }
  public Result apply(UUID collection,UUID job,JsonNode record,String mode) {
    try {return applyRecord(collection,job,record,mode);}
    catch(org.springframework.dao.DataAccessException e) {
      // Database diagnostics can contain the submitted source or metadata.
      throw new V2Exception(503,"database_unavailable","Database operation unavailable");
    }
  }
  private Result applyRecord(UUID collection,UUID job,JsonNode record,String mode) {
    if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("An active write transaction is required");
    Objects.requireNonNull(collection);Objects.requireNonNull(job);
    if(!Set.of("merge","replace").contains(mode)) throw V2Exception.badRequest("Invalid import mode");
    object(record,Set.of("schema_version","external_id","metadata","texts","embeddings","expected_revision"));
    if(record.has("schema_version") && !"1".equals(text(record,"schema_version",true)))
      throw V2Exception.badRequest("Unsupported schema_version");
    String external=text(record,"external_id",true);Long expected=expectedRevision(record);
    ObjectNode requestNode=mapper.createObjectNode();if(record.has("metadata"))requestNode.set("metadata",record.get("metadata"));
    if(record.has("texts"))requestNode.set("texts",record.get("texts"));
    DocumentWriteRequest request;
    try {request=mapper.treeToValue(requestNode,DocumentWriteRequest.class);}
    catch(java.io.IOException e) {throw V2Exception.badRequest("Invalid document fields");}
    var applied=documents.upsertInTransaction(collection,external,request,mode.equals("replace"),expected);
    UUID document=UUID.fromString(applied.body().path("id").asText());
    JsonNode embeddings=record.get("embeddings");if(embeddings==null) return new Result(document,0);
    if(!embeddings.isArray()) throw V2Exception.badRequest("embeddings must be an array");
    Map<String,SourceTextInput> provided=new HashMap<>();
    if(request.hasTexts() && request.texts()!=null) for(var source:request.texts()) provided.put(source.textId(),source);
    Set<String> seen=new HashSet<>();int inserted=0;
    for(JsonNode vector:embeddings) {
      object(vector,Set.of("indexation_id","profile_id","text_id","input_hash","values","on_conflict"));
      UUID index=uuid(text(vector,"indexation_id",true)),profile=uuid(text(vector,"profile_id",true));String textId=text(vector,"text_id",true);
      if(!seen.add(index+"/"+textId)) throw new V2Exception(422,"duplicate_vector","Duplicate indexation and text key");
      SourceTextInput source=provided.get(textId);
      if(source==null) throw new V2Exception(422,"text_not_in_record","Embedding text must appear in the same record");
      String hash=text(vector,"input_hash",true);if(!hash.matches("[0-9a-f]{64}"))throw new V2Exception(422,"invalid_input_hash","Invalid prepared input hash");
      String conflict=text(vector,"on_conflict",false);if(conflict==null)conflict="keep";
      if(!Set.of("keep","replace").contains(conflict))throw V2Exception.badRequest("Invalid on_conflict");
      Config config=config(collection,index,profile);
      if(!selected(config.selection(),source)) throw new V2Exception(422,"text_not_selected","Source text does not match index selection");
      String input=preparation.document(config.profile(),source.content(),properties.getMaxTextCodePoints());
      if(!sha256(input).equals(hash))throw new V2Exception(422,"invalid_input_hash","Prepared input hash does not match");
      float[] values=vectors.validate(vector.get("values"),config.profile().dimensions(),config.metric(),config.strategy());
      var rows=jdbc.query("SELECT id,revision FROM dvp.source_texts WHERE document_id=? AND text_key=?",
          (rs,n)->Map.entry(rs.getObject(1,UUID.class),rs.getLong(2)),document,textId);
      if(rows.isEmpty())throw new IllegalStateException("Imported source is unavailable");
      var row=rows.getFirst();int end=source.content().codePointCount(0,source.content().length());
      jdbc.update("""
          INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash)
          VALUES (?,?,?,?,?,?,0,0,?,?) ON CONFLICT (indexation_id,source_text_id,source_revision,ordinal) DO NOTHING
          """,UUID.randomUUID(),collection,index,document,row.getKey(),row.getValue(),end,hash);
      var units=jdbc.query("SELECT id,start_cp,end_cp,input_hash FROM dvp.vector_units WHERE indexation_id=? AND source_text_id=? AND source_revision=? AND ordinal=0",
          (rs,n)->new Unit(rs.getObject(1,UUID.class),rs.getInt(2),rs.getInt(3),rs.getString(4)),index,row.getKey(),row.getValue());
      if(units.size()!=1 || units.getFirst().start()!=0 || units.getFirst().end()!=end || !units.getFirst().hash().equals(hash))
        throw new V2Exception(422,"invalid_unit","Existing source unit does not match the prepared input");
      String sql="INSERT INTO dvp."+config.table()+" (unit_id,document_id,source_text_id,source_revision,indexation_id,embedding,input_hash,origin,origin_job_id) VALUES (?,?,?,?,?,?::vector,?,'imported',?) ";
      sql+=conflict.equals("keep")?"ON CONFLICT (unit_id) DO NOTHING":"ON CONFLICT (unit_id) DO UPDATE SET embedding=EXCLUDED.embedding,input_hash=EXCLUDED.input_hash,origin='imported',origin_job_id=EXCLUDED.origin_job_id,created_at=clock_timestamp()";
      inserted+=jdbc.update(sql,units.getFirst().id(),document,row.getKey(),row.getValue(),index,new PGvector(values).getValue(),hash,job);
    }
    return new Result(document,inserted);
  }
  private record Unit(UUID id,int start,int end,String hash) { }
  private record Config(String table,String metric,String strategy,JsonNode selection,ProfileResource profile) { }
  private Config config(UUID collection,UUID index,UUID profile) {
    return jdbc.query("""
        SELECT i.vector_table,i.metric,i.ann_strategy,i.selection::text,i.chunking::text,i.state,
               p.id,p.slug,p.model,p.model_revision,p.dimensions,p.execution_mode,p.provider_ref,p.preparation::text,p.fingerprint,p.created_at
        FROM dvp.indexations i JOIN dvp.embedding_profiles p ON p.id=i.profile_id
        WHERE i.id=? AND i.collection_id=? AND p.id=?
        """,(rs,n)->{
          try {
            String table=rs.getString(1);if(!table.equals("vec_"+index.toString().replace("-",""))) throw new IllegalStateException("Invalid vector table identifier");
            if(!"ready".equals(rs.getString(6)))throw new V2Exception(409,"indexation_unavailable","Index configuration is not ready");
            if(!"none".equals(mapper.readTree(rs.getString(5)).path("mode").asText()))throw new V2Exception(422,"chunking_not_supported","Imported vectors require chunking mode none");
            var p=new ProfileResource(rs.getObject(7,UUID.class),rs.getString(8),rs.getString(9),rs.getString(10),rs.getInt(11),rs.getString(12),rs.getString(13),
                mapper.readValue(rs.getString(14),ProfileRequest.Preparation.class),rs.getString(15),"http".equals(rs.getString(12)),rs.getTimestamp(16).toInstant());
            return new Config(table,rs.getString(2),rs.getString(3),mapper.readTree(rs.getString(4)),p);
          } catch(java.io.IOException e) {throw new IllegalStateException("Invalid stored index configuration");}
        },index,collection,profile).stream().findFirst().orElseThrow(()->new V2Exception(404,"indexation_not_found","Index configuration or profile does not match collection"));
  }
  private boolean selected(JsonNode selection,SourceTextInput source) {return matches(selection.path("kinds"),source.kind()) && matches(selection.path("languages"),source.language());}
  private boolean matches(JsonNode allowed,String value) {if(allowed.isMissingNode() || allowed.isEmpty())return true;if(value==null)return false;for(JsonNode item:allowed)if(item.isTextual() && value.equals(item.textValue()))return true;return false;}
  private static void object(JsonNode value,Set<String> fields) {if(value==null || !value.isObject())throw V2Exception.badRequest("Expected object");value.fieldNames().forEachRemaining(name->{if(!fields.contains(name))throw V2Exception.badRequest("Unknown field");});}
  private static String text(JsonNode value,String field,boolean required) {JsonNode node=value.get(field);if(node==null && !required)return null;if(node==null || !node.isTextual())throw V2Exception.badRequest("Invalid "+field);return node.textValue();}
  private static Long expectedRevision(JsonNode record) {JsonNode value=record.get("expected_revision");if(value==null)return null;if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<1)throw V2Exception.badRequest("Invalid expected_revision");return value.longValue();}
  private static UUID uuid(String value) {try {UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException();return id;}catch(RuntimeException e){throw V2Exception.badRequest("Invalid UUID");}}
  private String sha256(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
