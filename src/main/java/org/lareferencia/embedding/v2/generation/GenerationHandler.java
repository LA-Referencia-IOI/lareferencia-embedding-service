package org.lareferencia.embedding.v2.generation;

import com.fasterxml.jackson.databind.*;
import com.pgvector.PGvector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.indexation.HalfvecPolicy;
import org.lareferencia.embedding.v2.job.*;
import org.lareferencia.embedding.v2.preparation.PreparationHandler;
import org.lareferencia.embedding.v2.preparation.PreparationService;
import org.lareferencia.embedding.v2.profile.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class GenerationHandler implements JobHandler {
  private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final PreparationService preparation;
  private final ProfilePreparation textPreparation;private final ProviderRegistry providers;private final DvpProperties properties;
  public GenerationHandler(@Qualifier("jdbcTemplate") JdbcTemplate jdbc,ObjectMapper mapper,PreparationService preparation,
      ProfilePreparation textPreparation,ProviderRegistry providers,DvpProperties properties) {
    this.jdbc=jdbc;this.mapper=mapper;this.preparation=preparation;this.textPreparation=textPreparation;this.providers=providers;this.properties=properties;
  }
  public String kind() { return "generate"; }
  public JsonNode publicParameters(JsonNode internal) { return internal.deepCopy(); }
  public void run(JobRepository.Lease lease,JobRepository jobs) {
    var job=jobs.find(lease.jobId()).orElseThrow(JobRepository.LeaseLostException::new);
    jobs.initializeEnumeration(lease);jobs.materialize(lease);
    for(int n=0;n<Math.min(100,properties.getDocumentBatchSize());n++) {
      var candidate=jobs.claimItem(lease);if(candidate.isEmpty()) break;var item=candidate.get();
      if(item.documentId()==null) { skip(jobs,lease,item);continue; }
      var held=jobs.tryDocumentLock(job.indexationId(),item.documentId());
      if(held.isEmpty()) { jobs.retryOrFail(lease,item,"document_busy",1000);continue; }
      try(var lock=held.get()) {
        var progress=new HashMap<String,Long>();
        Map<UUID,Long> sourceSnapshot=new HashMap<>();
        jobs.progress(lease,item,lock,progress,tx->{
          preparation.prepareDocument(tx,job,item,progress);sourceSnapshot.putAll(sourceRevisions(tx,item.documentId()));
        });
        var config=config(job);
        for(;;) {
          lock.requireAvailable();var units=missing(job,item,config);
          if(units.isEmpty()) break;
          Map<String,float[]> resolved=new LinkedHashMap<>();Map<String,String> origins=new HashMap<>();
          for(Unit unit:units) if(!resolved.containsKey(unit.input())) {
            var cached=reusable(job,config,unit);
            if(cached.isPresent()) {resolved.put(unit.input(),cached.get());origins.put(unit.input(),"reused");}
            else resolved.put(unit.input(),null);
          }
          var pending=resolved.entrySet().stream().filter(e->e.getValue()==null).map(Map.Entry::getKey).toList();
          if(!pending.isEmpty()) {
            var vectors=providers.embed(config.profile(),pending);
            if(vectors.size()!=pending.size()) throw invalidVector();
            for(int k=0;k<pending.size();k++) {validate(vectors.get(k),config);resolved.put(pending.get(k),vectors.get(k));origins.put(pending.get(k),"generated");}
          }
          for(float[] values:resolved.values()) validate(values,config);
          Map<String,Long> counters=new HashMap<>();
          jobs.progress(lease,item,lock,counters,tx->{
            lockDocument(tx,job,item,sourceSnapshot);
            for(Unit unit:units) {
              boolean current=Boolean.TRUE.equals(tx.queryForObject("""
                  SELECT EXISTS(SELECT 1 FROM dvp.vector_units u JOIN dvp.source_texts s ON s.id=u.source_text_id
                    WHERE u.id=? AND u.document_id=? AND u.indexation_id=? AND u.source_revision=s.revision AND u.input_hash=?)
                  """,Boolean.class,unit.id(),item.documentId(),job.indexationId(),unit.hash()));
              if(!current) throw new SourceChanged();
            }
            for(Unit unit:units) {
              String origin=origins.get(unit.input());
              int inserted=tx.update("INSERT INTO dvp."+config.table()+" (unit_id,document_id,source_text_id,source_revision,indexation_id,embedding,input_hash,origin,origin_job_id) VALUES (?,?,?,?,?,?::vector,?,?,?) ON CONFLICT (unit_id) DO NOTHING",
                  unit.id(),item.documentId(),unit.source(),unit.revision(),job.indexationId(),new PGvector(resolved.get(unit.input())).getValue(),unit.hash(),origin,job.id());
              if(inserted>0) counters.merge(origin.equals("reused")?"vectors_reused":"vectors_generated",1L,Long::sum);
            }
          });
        }
        jobs.completeWithAdvisoryLock(lease,item,"succeeded",null,Map.of("documents_read",1L,"documents_processed",1L),null,lock,tx->lockDocument(tx,job,item,sourceSnapshot));
      } catch(PreparationHandler.DocumentGone | SourceChanged e) { skip(jobs,lease,item); }
      catch(ProviderRegistry.ProviderFailure e) { jobs.recordFailure(lease,item,e.code(),e.upstreamStatus(),false,e.retryAfter()); }
      catch(V2Exception e) {
        if(e.status()==503 || e.status()==429) jobs.recordFailure(lease,item,e.code(),e.status(),false,null);
        else jobs.complete(lease,item,"failed",e.code(),Map.of("documents_read",1L,"documents_failed",1L),null,tx->{});
      } catch(org.springframework.dao.TransientDataAccessException e) {jobs.retryOrFail(lease,item,"database_contention",1000);}
    }
  }
  private void skip(JobRepository jobs,JobRepository.Lease lease,JobRepository.ItemLease item) {
    jobs.complete(lease,item,"skipped","source_changed",Map.of("documents_read",1L,"items_skipped",1L),null,tx->{});
  }
  private record Config(String table,String metric,String strategy,ProfileResource profile) { }
  private record Unit(UUID id,UUID source,long revision,String hash,String input) {
    @Override public String toString() {return "Unit[redacted]";}
  }
  private Config config(JobRepository.Job job) {
    return jdbc.query("""
        SELECT i.vector_table,i.metric,i.ann_strategy,i.state,p.id,p.slug,p.model,p.model_revision,p.dimensions,p.execution_mode,p.provider_ref,p.preparation::text,p.fingerprint,p.created_at
        FROM dvp.indexations i JOIN dvp.embedding_profiles p ON p.id=i.profile_id WHERE i.id=? AND i.collection_id=?
        """,(rs,n)->{
          try {
            if(!"ready".equals(rs.getString(4))) throw new V2Exception(409,"indexation_unavailable","Index configuration is not ready");
            String table=rs.getString(1),expected="vec_"+job.indexationId().toString().replace("-","");
            if(!expected.equals(table)) throw new IllegalStateException("Invalid vector table identifier");
            var prep=mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).readValue(rs.getString(12),ProfileRequest.Preparation.class);
            var p=new ProfileResource(rs.getObject(5,UUID.class),rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(9),rs.getString(10),rs.getString(11),prep,rs.getString(13),"http".equals(rs.getString(10)),rs.getTimestamp(14).toInstant());
            if(!p.inferenceCapable()) throw new V2Exception(409,"profile_not_executable","Profile has no inference capability");
            return new Config(table,rs.getString(2),rs.getString(3),p);
          } catch(java.io.IOException e) {throw new IllegalStateException("Invalid profile preparation");}
        },job.indexationId(),job.collectionId()).stream().findFirst().orElseThrow(SourceChanged::new);
  }
  private List<Unit> missing(JobRepository.Job job,JobRepository.ItemLease item,Config c) {
    int limit=Math.min(16,Math.min(properties.getProviderBatchSize(),providers.batchSize(c.profile())));
    return jdbc.query("""
        SELECT u.id,u.source_text_id,u.source_revision,u.input_hash,
          substring(s.content FROM (u.start_cp+1) FOR (u.end_cp-u.start_cp)) AS fragment,u.end_cp-u.start_cp AS size
        FROM dvp.vector_units u JOIN dvp.source_texts s ON s.id=u.source_text_id AND s.revision=u.source_revision
        LEFT JOIN dvp.%s v ON v.unit_id=u.id
        WHERE u.indexation_id=? AND u.document_id=? AND v.unit_id IS NULL ORDER BY u.id LIMIT ?
        """.formatted(c.table()),(rs,n)->{
          String fragment=rs.getString(5);if(fragment.codePointCount(0,fragment.length())!=rs.getInt(6)) throw new V2Exception(422,"invalid_unit","Prepared unit is invalid");
          String input=textPreparation.document(c.profile(),fragment,properties.getMaxTextCodePoints());String hash=rs.getString(4);
          if(!hash.equals(sha256(input))) throw new V2Exception(422,"invalid_unit","Prepared unit hash is invalid");
          return new Unit(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),hash,input);
        },job.indexationId(),item.documentId(),limit);
  }
  private Optional<float[]> reusable(JobRepository.Job job,Config c,Unit unit) {
    return jdbc.query("""
        SELECT v.embedding::text,substring(s.content FROM (u.start_cp+1) FOR (u.end_cp-u.start_cp))
        FROM dvp.%s v JOIN dvp.vector_units u ON u.id=v.unit_id
        JOIN dvp.source_texts s ON s.id=u.source_text_id AND s.revision=u.source_revision
        WHERE u.indexation_id=? AND u.input_hash=? AND v.input_hash=u.input_hash
          AND v.source_revision=u.source_revision AND v.indexation_id=u.indexation_id ORDER BY u.id LIMIT 100
        """.formatted(c.table()),(rs,n)->{
          try {
            String input=textPreparation.document(c.profile(),rs.getString(2),properties.getMaxTextCodePoints());
            return input.equals(unit.input())?new PGvector(rs.getString(1)).toArray():null;
          } catch(java.sql.SQLException e) {throw new IllegalStateException("Invalid stored vector");}
        },job.indexationId(),unit.hash()).stream().filter(Objects::nonNull).findFirst();
  }
  private Map<UUID,Long> sourceRevisions(JdbcTemplate tx,UUID document) {
    Map<UUID,Long> result=new HashMap<>();
    tx.query("SELECT id,revision FROM dvp.source_texts WHERE document_id=?",rs->{result.put(rs.getObject(1,UUID.class),rs.getLong(2));},document);
    return result;
  }
  private void lockDocument(JdbcTemplate tx,JobRepository.Job job,JobRepository.ItemLease item,Map<UUID,Long> sourceSnapshot) {
    Boolean exists=tx.query("SELECT id FROM dvp.documents WHERE id=? AND collection_id=? FOR UPDATE",(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)rs->rs.next(),item.documentId(),job.collectionId());
    if(!Boolean.TRUE.equals(exists)) throw new SourceChanged();
    if(!sourceSnapshot.equals(sourceRevisions(tx,item.documentId()))) throw new SourceChanged();
    Boolean ready=tx.queryForObject("SELECT EXISTS(SELECT 1 FROM dvp.indexations WHERE id=? AND collection_id=? AND state='ready')",Boolean.class,job.indexationId(),job.collectionId());
    if(!Boolean.TRUE.equals(ready)) throw new V2Exception(409,"indexation_unavailable","Index configuration is not ready");
  }
  private void validate(float[] values,Config config) {
    if(values==null || values.length!=config.profile().dimensions()) throw invalidVector();
    boolean nonzero=false;for(float v:values) {if(!Float.isFinite(v)) throw invalidVector();if(v!=0)nonzero=true;}
    if(config.metric().equals("cosine") && !nonzero) throw invalidVector();
    if(config.strategy().equals("hnsw_halfvec") && !HalfvecPolicy.compatible(values,config.metric()))
      throw new V2Exception(422,"halfvec_not_representable","Vector cannot be represented by halfvec");
  }
  private V2Exception invalidVector() {return new V2Exception(422,"invalid_vector","Provider returned an invalid vector");}
  private String sha256(String input) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  private static final class SourceChanged extends RuntimeException { }
}
