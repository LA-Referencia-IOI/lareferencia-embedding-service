package org.lareferencia.embedding.v2.preparation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.job.JobRepository;
import org.lareferencia.embedding.v2.profile.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PreparationService {
  private final ObjectMapper mapper;private final TextChunker chunker;
  private final ProfilePreparation preparation;private final DvpProperties properties;
  public PreparationService(ObjectMapper mapper,TextChunker chunker,ProfilePreparation preparation,DvpProperties properties) {
    this.mapper=mapper;this.chunker=chunker;this.preparation=preparation;this.properties=properties;
  }
  private record Text(UUID id,String kind,String language,String content,long revision) {
    @Override public String toString() {return "Text[redacted]";}
  }
  public void prepareDocument(JdbcTemplate jdbc,JobRepository.Job job,JobRepository.ItemLease item,Map<String,Long> increments) {
    if(item.documentId()==null) throw new PreparationHandler.DocumentGone();
    var document=jdbc.query("SELECT id FROM dvp.documents WHERE id=? AND collection_id=? FOR UPDATE",
        (rs,n)->rs.getObject(1,UUID.class),item.documentId(),job.collectionId());
    if(document.isEmpty()) throw new PreparationHandler.DocumentGone();
    var configs=jdbc.query("""
        SELECT i.selection::text,i.chunking::text,i.state,p.id,p.slug,p.model,p.model_revision,p.dimensions,
               p.execution_mode,p.provider_ref,p.preparation::text,p.fingerprint,p.created_at
        FROM dvp.indexations i JOIN dvp.embedding_profiles p ON p.id=i.profile_id
        WHERE i.id=? AND i.collection_id=?
        """,(rs,n)->{
          try {
            var settings=mapper.copy().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
            var prep=settings.readValue(rs.getString(11),ProfileRequest.Preparation.class);
            var profile=new ProfileResource(rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getString(7),
                rs.getInt(8),rs.getString(9),rs.getString(10),prep,rs.getString(12),"http".equals(rs.getString(9)),rs.getTimestamp(13).toInstant());
            return new Config(mapper.readTree(rs.getString(1)),mapper.readTree(rs.getString(2)),rs.getString(3),profile);
          } catch(java.io.IOException e) { throw new IllegalStateException("Invalid stored preparation configuration"); }
        },job.indexationId(),job.collectionId());
    if(configs.isEmpty() || !"ready".equals(configs.getFirst().state())) throw new V2Exception(409,"indexation_unavailable","Index configuration is not ready");
    var config=configs.getFirst();
    var texts=jdbc.query("SELECT id,kind,language,content,revision FROM dvp.source_texts WHERE document_id=? ORDER BY id",
        (rs,n)->new Text(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)),item.documentId());
    long[] inserted={0};
    for(var text:texts) {
      if(!selected(config.selection(),text)) continue;
      String mode=config.chunking().path("mode").asText();int max=config.chunking().path("max_chars").asInt(2000),overlap=config.chunking().path("overlap_chars").asInt(200);
      chunker.forEach(text.content(),mode,max,overlap,chunk->{
        String input=preparation.document(config.profile(),chunk.text(),properties.getMaxTextCodePoints());
        inserted[0]+=jdbc.update("""
            INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (indexation_id,source_text_id,source_revision,ordinal) DO NOTHING
            """,UUID.randomUUID(),job.collectionId(),job.indexationId(),item.documentId(),text.id(),text.revision(),
            chunk.ordinal(),chunk.start(),chunk.end(),sha256(input));
      });
    }
    increments.put("units_prepared",inserted[0]);
  }
  private record Config(JsonNode selection,JsonNode chunking,String state,ProfileResource profile) { }
  private boolean selected(JsonNode selection,Text text) {
    return matches(selection.path("kinds"),text.kind()) && matches(selection.path("languages"),text.language());
  }
  private boolean matches(JsonNode options,String value) {
    if(options.isMissingNode() || options.isEmpty()) return true;
    if(value==null) return false;
    for(JsonNode option:options) if(option.isTextual() && value.equals(option.textValue())) return true;
    return false;
  }
  private String sha256(String input) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
    catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
