package org.lareferencia.embedding.v2.search;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.importing.ImportVectorValidator;
import org.lareferencia.embedding.v2.profile.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class SearchService {
  private final JdbcTemplate jdbc;private final TransactionTemplate reads;private final DvpProperties props;
  private final ProfileRepository profiles;private final ProfilePreparation preparation;private final ProviderRegistry providers;
  private final ImportVectorValidator validator;private final ObjectMapper mapper;private final Semaphore exactSlots,approxSlots;
  record Config(UUID id,UUID profile,String table,String metric,String strategy,String state,String annState) {}
  record Hit(UUID document,UUID unit,String external,String textKey,long sourceRevision,double distance,String text,String metadata) {}
  public SearchService(@Qualifier("readJdbc") JdbcTemplate jdbc,@Qualifier("readTransactions") TransactionTemplate reads,DvpProperties props,
      ProfileRepository profiles,ProfilePreparation preparation,ProviderRegistry providers,ImportVectorValidator validator,ObjectMapper mapper) {
    this.jdbc=jdbc;this.reads=reads;this.props=props;this.profiles=profiles;this.preparation=preparation;this.providers=providers;this.validator=validator;this.mapper=mapper;
    exactSlots=new Semaphore(props.getExactConcurrency());approxSlots=new Semaphore(props.getApproximateConcurrency());
  }
  public Map<String,Object> search(String collection,String indexation,JsonNode request) {
    UUID c=uuid(collection),i=uuid(indexation);fields(request,Set.of("query","top_n","result_unit","mode","include"));
    JsonNode query=request.get("query");fields(query,Set.of("text","vector"));
    boolean hasText=query.has("text"),hasVector=query.has("vector");if(hasText==hasVector)throw V2Exception.badRequest("Provide exactly one query text or vector");
    int top=10;if(request.has("top_n")){JsonNode n=request.get("top_n");if(!n.isIntegralNumber()||!n.canConvertToInt())throw V2Exception.badRequest("Invalid top_n");top=n.intValue();}
    if(top<1||top>100)throw V2Exception.badRequest("Invalid top_n");
    String unit=option(request,"result_unit","document",Set.of("document","unit"));String mode=option(request,"mode","approximate",Set.of("exact","approximate"));
    Set<String> includes=new HashSet<>();if(request.has("include")){var array=request.get("include");if(!array.isArray())throw V2Exception.badRequest("Invalid include");for(var n:array)if(!n.isTextual()||!Set.of("texts","metadata").contains(n.textValue())||!includes.add(n.textValue()))throw V2Exception.badRequest("Invalid include");}
    Semaphore slots=mode.equals("exact")?exactSlots:approxSlots;if(!slots.tryAcquire())throw new V2Exception(429,"search_busy","Search capacity exhausted");
    try {
      Config config=config(c,i);check(config,mode);ProfileResource profile=profiles.find(config.profile()).orElseThrow(()->new V2Exception(503,"database_unavailable","Profile unavailable"));
      float[] vector;
      if(hasText) {
        var value=query.get("text");if(!value.isTextual())throw V2Exception.badRequest("Invalid query text");String text=value.textValue();
        if(text.codePointCount(0,text.length())>props.getMaxQueryCodePoints())throw new V2Exception(422,"invalid_input","Query exceeds its limit");
        if(!profile.inferenceCapable())throw new V2Exception(409,"profile_not_executable","Profile has no inference capability");
        vector=providers.embed(profile,List.of(preparation.query(profile,text,props.getMaxQueryCodePoints()))).getFirst();
        vector=validator.validate(mapper.valueToTree(vector),profile.dimensions(),config.metric(),mode.equals("approximate")?config.strategy():"none");
      } else vector=validator.validate(query.get("vector"),profile.dimensions(),config.metric(),mode.equals("approximate")?config.strategy():"none");
      String value=Arrays.toString(vector);int requested=top;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(mode.equals("exact")?props.getExactTimeoutSeconds():props.getApproximateTimeoutSeconds());
      try {return reads.execute(tx->{Config current=config(c,i);check(current,mode);return run(c,current,value,requested,unit,mode,includes,deadline);});}
      catch(DataAccessException | org.springframework.transaction.TransactionTimedOutException e) {if(timeout(e))throw new V2Exception(503,"search_timeout","Search timed out");throw e;}
    } finally {slots.release();}
  }
  private Config config(UUID collection,UUID id) {
    var found=jdbc.query("SELECT id,profile_id,vector_table,metric,ann_strategy,state,ann_state FROM dvp.indexations WHERE collection_id=? AND id=?",(rs,n)->new Config(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)),collection,id);
    if(found.isEmpty())throw new V2Exception(404,"not_found","Resource not found");Config result=found.getFirst();if(!result.table().equals("vec_"+id.toString().replace("-","")))throw new IllegalStateException("Invalid vector table identity");return result;
  }
  private void check(Config c,String mode) {
    if(!c.state().equals("ready"))throw new V2Exception(409,"indexation_unavailable","Index configuration is not ready");
    if(mode.equals("approximate") && (!c.annState().equals("ready")||c.strategy().equals("none")))throw new V2Exception(409,"approximate_search_unavailable","Approximate search unavailable");
  }
  private Map<String,Object> run(UUID collection,Config c,String vector,int top,String unit,String mode,Set<String> includes,long deadline) {
    String op=switch(c.metric()){case "cosine"->"<=>";case "l2"->"<->";case "inner_product"->"<#>";default->throw new IllegalStateException("Invalid metric");};
    List<Hit> hits;
    if(mode.equals("exact")) {
      timeout(deadline);String sql="WITH distances AS MATERIALIZED (SELECT v.unit_id,v.document_id,v.source_text_id,v.source_revision,(v.embedding "+op+" ?::vector) distance FROM dvp."+c.table()+" v JOIN dvp.vector_units u ON u.id=v.unit_id AND u.document_id=v.document_id AND u.source_text_id=v.source_text_id AND u.source_revision=v.source_revision AND u.indexation_id=v.indexation_id JOIN dvp.source_texts s ON s.id=u.source_text_id AND s.document_id=u.document_id AND s.revision=u.source_revision JOIN dvp.documents d ON d.id=u.document_id AND d.collection_id=u.collection_id WHERE u.indexation_id=? AND u.collection_id=?), ranked AS (SELECT *,row_number() OVER (PARTITION BY document_id ORDER BY distance,unit_id) rn FROM distances) SELECT r.document_id,r.unit_id,d.external_id,s.text_key,r.source_revision,r.distance,"+(includes.contains("texts")?"substring(s.content from u.start_cp+1 for u.end_cp-u.start_cp)":"NULL::text")+","+(includes.contains("metadata")?"d.metadata::text":"NULL::text")+" FROM ranked r JOIN dvp.vector_units u ON u.id=r.unit_id JOIN dvp.source_texts s ON s.id=r.source_text_id JOIN dvp.documents d ON d.id=r.document_id "+(unit.equals("document")?"WHERE rn=1 ":"")+"ORDER BY r.distance,r.document_id,r.unit_id LIMIT ?";
      hits=jdbc.query(sql,(rs,n)->hit(rs),vector,c.id(),collection,top);
    } else {
      String indexOp=(c.strategy().equals("hnsw_halfvec")?"halfvec_":"vector_")+switch(c.metric()){case "cosine"->"cosine_ops";case "l2"->"l2_ops";case "inner_product"->"ip_ops";default->throw new IllegalStateException("Invalid metric");};
      String indexExpression=c.strategy().equals("hnsw_halfvec")?"(embedding::halfvec("+dimensions(vector)+"))":"embedding";
      Integer ready=jdbc.queryForObject("SELECT count(*) FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_index x ON x.indrelid=t.oid JOIN pg_class idx ON idx.oid=x.indexrelid JOIN pg_am am ON am.oid=idx.relam JOIN pg_opclass opc ON opc.oid=x.indclass[0] WHERE n.nspname='dvp' AND t.relname=? AND idx.relname=? AND x.indisvalid AND x.indisready AND x.indislive AND x.indpred IS NULL AND x.indnatts=1 AND am.amname='hnsw' AND opc.opcname=? AND pg_get_indexdef(x.indexrelid,1,true)=?",Integer.class,c.table(),c.table()+"_ann",indexOp,indexExpression);
      if(ready==null || ready!=1)throw new V2Exception(409,"approximate_search_unavailable","Approximate search unavailable");
      jdbc.execute("SET LOCAL enable_seqscan = off");
      Map<UUID,Hit> accumulated=new HashMap<>();int budget=Math.max(100,top*10);
      while(true) {
        timeout(deadline);jdbc.execute("SET LOCAL hnsw.ef_search = "+Math.min(1000,budget));jdbc.execute("SET LOCAL hnsw.iterative_scan = 'strict_order'");jdbc.execute("SET LOCAL hnsw.max_scan_tuples = 10000");
        String candidate=c.strategy().equals("hnsw_halfvec")?"(v.embedding::halfvec("+dimensions(vector)+")) "+op+" (?::vector)::halfvec("+dimensions(vector)+")":"v.embedding "+op+" ?::vector";
        String sql="WITH candidates AS MATERIALIZED (SELECT v.unit_id,v.document_id,v.source_text_id,v.source_revision,v.indexation_id,v.embedding FROM dvp."+c.table()+" v ORDER BY "+candidate+" LIMIT ?) SELECT v.document_id,v.unit_id,d.external_id,s.text_key,v.source_revision,(v.embedding "+op+" ?::vector) distance,"+"NULL::text,NULL::text"+" FROM candidates v JOIN dvp.vector_units u ON u.id=v.unit_id AND u.document_id=v.document_id AND u.source_text_id=v.source_text_id AND u.source_revision=v.source_revision AND u.indexation_id=v.indexation_id JOIN dvp.source_texts s ON s.id=u.source_text_id AND s.document_id=u.document_id AND s.revision=u.source_revision JOIN dvp.documents d ON d.id=u.document_id AND d.collection_id=u.collection_id WHERE u.indexation_id=? AND u.collection_id=?";
        for(Hit h:jdbc.query(sql,(rs,n)->hit(rs),vector,budget,vector,c.id(),collection))accumulated.put(h.unit(),h);
        hits=rank(accumulated.values(),unit,top);if(hits.size()>=top||budget>=10000)break;budget=Math.min(10000,budget*2);
      }
    }
    List<Map<String,Object>> results=new ArrayList<>();for(Hit h:hits){Map<String,Object> row=new LinkedHashMap<>();row.put("document_id",h.document());row.put("external_id",h.external());row.put("unit_id",h.unit());row.put("text_id",h.textKey());row.put("source_revision",h.sourceRevision());row.put("distance",h.distance());String text=h.text(),metadata=h.metadata();
      if(mode.equals("approximate") && !includes.isEmpty()) {
        timeout(deadline);
        var projection=jdbc.queryForObject("SELECT "+(includes.contains("texts")?"substring(s.content from u.start_cp+1 for u.end_cp-u.start_cp)":"NULL::text")+","+(includes.contains("metadata")?"d.metadata::text":"NULL::text")+" FROM dvp.vector_units u JOIN dvp.source_texts s ON s.id=u.source_text_id AND s.document_id=u.document_id AND s.revision=u.source_revision JOIN dvp.documents d ON d.id=u.document_id WHERE u.id=? AND u.document_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},h.unit(),h.document());
        text=projection[0];metadata=projection[1];
      }
      if(includes.contains("texts"))row.put("text",text);if(includes.contains("metadata"))row.put("metadata",json(metadata));results.add(row);}
    Map<String,Object> response=new LinkedHashMap<>();response.put("indexation_id",c.id());response.put("profile_id",c.profile());response.put("metric",c.metric());response.put("mode",mode);response.put("result_unit",unit);response.put("requested",top);response.put("returned",results.size());response.put("shortfall_reason",results.size()<top?(mode.equals("exact")?"exhausted":"candidate_limit"):null);response.put("recall_guaranteed",mode.equals("exact"));response.put("results",results);return response;
  }
  private static List<Hit> rank(Collection<Hit> hits,String unit,int limit) {var sorted=hits.stream().sorted(Comparator.comparingDouble(Hit::distance).thenComparing(h->h.document().toString()).thenComparing(h->h.unit().toString())).toList();if(unit.equals("unit"))return sorted.stream().limit(limit).toList();Set<UUID> seen=new HashSet<>();return sorted.stream().filter(h->seen.add(h.document())).limit(limit).toList();}
  private static Hit hit(java.sql.ResultSet rs)throws java.sql.SQLException{return new Hit(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getDouble(6),rs.getString(7),rs.getString(8));}
  private void timeout(long deadline){long remaining=deadline-System.nanoTime();if(remaining<=0)throw new V2Exception(503,"search_timeout","Search timed out");jdbc.execute("SET LOCAL statement_timeout = "+Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));}
  private static boolean timeout(Throwable e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof org.springframework.transaction.TransactionTimedOutException || t instanceof java.sql.SQLException s && "57014".equals(s.getSQLState()))return true;return false;}
  private JsonNode json(String s){try{return mapper.readTree(s);}catch(java.io.IOException e){throw new IllegalStateException("Invalid stored JSON");}}
  private static int dimensions(String vector){return vector.split(",",-1).length;}
  private static UUID uuid(String s){try{UUID id=UUID.fromString(s);if(!id.toString().equalsIgnoreCase(s))throw new IllegalArgumentException();return id;}catch(RuntimeException e){throw V2Exception.badRequest("Invalid UUID");}}
  private static String option(JsonNode n,String field,String def,Set<String> allowed){if(!n.has(field))return def;var v=n.get(field);if(!v.isTextual()||!allowed.contains(v.textValue()))throw V2Exception.badRequest("Invalid "+field);return v.textValue();}
  private static void fields(JsonNode n,Set<String> allowed){if(n==null||!n.isObject())throw V2Exception.badRequest("Invalid search request");n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw V2Exception.badRequest("Unknown search field");});}
}
