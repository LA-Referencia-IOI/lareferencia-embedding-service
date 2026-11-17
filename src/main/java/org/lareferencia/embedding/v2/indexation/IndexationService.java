package org.lareferencia.embedding.v2.indexation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.lareferencia.embedding.v2.common.*;
import org.lareferencia.embedding.v2.job.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

@Service
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class IndexationService {
  private final JdbcTemplate write,read; private final TransactionTemplate reads;
  private final ObjectMapper mapper; private final IdempotencyService idempotency;private final CanonicalJson canonical;
  private final CursorCodec cursors;private final JobRepository jobs;
  public IndexationService(@Qualifier("jdbcTemplate") JdbcTemplate write,@Qualifier("readJdbc") JdbcTemplate read,
      @Qualifier("readTransactions") TransactionTemplate reads,ObjectMapper mapper,IdempotencyService idempotency,
      CanonicalJson canonical,CursorCodec cursors,JobRepository jobs) {
    this.write=write;this.read=read;this.reads=reads;this.mapper=mapper;this.idempotency=idempotency;
    this.canonical=canonical;this.cursors=cursors;this.jobs=jobs;
  }
  public IdempotencyService.Result create(String collection,JsonNode raw,String key,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of());UUID c=uuid(collection);object(raw,Set.of("slug","profile_id","selection","chunking","metric","ann_strategy"));
    var definition=definition(raw);
    UUID profile=uuid(text(raw,"profile_id",null));
    String path=path(c);
    return idempotency.execute("POST "+path,key,canonical.requestHash(query,raw),()->{
      if(!exists(write,"SELECT EXISTS(SELECT 1 FROM dvp.collections WHERE id=?)",c)) throw new V2Exception(404,"collection_not_found","Collection does not exist");
      var dims=write.query("SELECT dimensions FROM dvp.embedding_profiles WHERE id=?",(rs,n)->rs.getInt(1),profile);
      if(dims.isEmpty()) throw new V2Exception(404,"profile_not_found","Profile does not exist");
      validateDimensions(definition.strategy(),dims.getFirst());
      UUID id=UUID.randomUUID();
      int inserted=insert(c,profile,id,definition);
      if(inserted==0) throw new V2Exception(409,"indexation_exists","Index configuration slug already exists");
      var job=jobs.insert(UUID.randomUUID(),c,id,null,"maintenance",mapper.createObjectNode().put("operation","provision"));
      ObjectNode response=(ObjectNode)fetch(write,c,id);response.put("job_id",job.id().toString());
      return new IdempotencyService.Result(202,response,Map.of("Location",path+"/"+id),job.id());
    });
  }
  /** Resolve or atomically create an inline import indexation in the caller's write transaction. */
  public UUID resolveForImport(UUID collection,UUID profile,int dimensions,JsonNode raw) {
    if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
        || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("An active write transaction is required");
    object(raw,Set.of("slug","selection","chunking","metric","ann_strategy"));
    var definition=definition(raw);
    validateDimensions(definition.strategy(),dimensions);
    if(!exists(write,"SELECT EXISTS(SELECT 1 FROM dvp.collections WHERE id=?)",collection))throw new V2Exception(404,"collection_not_found","Collection does not exist");
    UUID id=UUID.randomUUID();
    int inserted=insert(collection,profile,id,definition);
    if(inserted==1) {
      IndexTableProvisioner.create(write,id,table(id),dimensions);
      write.update("UPDATE dvp.indexations SET state='ready' WHERE id=?",id);
      return id;
    }
    var existing=write.query("SELECT id,profile_id,selection::text,chunking::text,metric,ann_strategy,state FROM dvp.indexations WHERE collection_id=? AND slug=?",(rs,n)->{
      try {return new ImportIndex(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),mapper.readTree(rs.getString(3)),mapper.readTree(rs.getString(4)),rs.getString(5),rs.getString(6),rs.getString(7));}
      catch(java.io.IOException e){throw new IllegalStateException("Invalid stored index configuration");}
    },collection,definition.slug());
    if(existing.isEmpty())throw new V2Exception(409,"indexation_conflict","Index configuration changed concurrently");
    var match=existing.getFirst();if(!profile.equals(match.profile()) || !definition.selection().equals(match.selection()) || !definition.chunking().equals(match.chunking()) || !definition.metric().equals(match.metric()) || !definition.strategy().equals(match.strategy()))
      throw new V2Exception(409,"indexation_conflict","Index configuration conflicts with an existing resource");
    if(!"ready".equals(match.state()))throw new V2Exception(409,"indexation_unavailable","Index configuration is not ready");
    return match.id();
  }
  private record Definition(String slug,ObjectNode selection,ObjectNode chunking,String metric,String strategy) { }
  private Definition definition(JsonNode raw) {
    String slug=text(raw,"slug",null);
    if(slug==null || !slug.matches("[a-z0-9][a-z0-9_-]{0,99}")) throw V2Exception.badRequest("Invalid slug");
    String metric=text(raw,"metric","cosine"),strategy=text(raw,"ann_strategy","hnsw_vector");
    if(!Set.of("cosine","l2","inner_product").contains(metric) || !Set.of("hnsw_vector","hnsw_halfvec","none").contains(strategy))
      throw V2Exception.badRequest("Invalid metric or ANN strategy");
    return new Definition(slug,selection(raw.get("selection")),chunking(raw.get("chunking")),metric,strategy);
  }
  private static void validateDimensions(String strategy,int dimensions) {
    if((strategy.equals("hnsw_vector") && dimensions>2000) || (strategy.equals("hnsw_halfvec") && dimensions>4000))
      throw new V2Exception(422,"ann_dimensions_unsupported","Use an explicitly supported strategy for this dimension");
  }
  private int insert(UUID collection,UUID profile,UUID id,Definition definition) {
    return write.update("""
        INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state)
        VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?,?,'provisioning',?) ON CONFLICT (collection_id,slug) DO NOTHING
        """,id,collection,definition.slug(),profile,definition.selection().toString(),definition.chunking().toString(),
        definition.metric(),definition.strategy(),table(id),definition.strategy().equals("none")?"disabled":"pending");
  }
  private record ImportIndex(UUID id,UUID profile,JsonNode selection,JsonNode chunking,String metric,String strategy,String state) { }
  public IdempotencyService.Result maintenance(String collection,String id,JsonNode body,String key,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of());UUID c=uuid(collection),i=uuid(id);object(body,Set.of("operation"));String operation=text(body,"operation",null);
    if(operation==null || !Set.of("reconcile","build_ann").contains(operation)) throw V2Exception.badRequest("Invalid maintenance operation");
    String route=path(c)+"/"+i+"/maintenance-jobs";
    return idempotency.execute("POST "+route,key,canonical.requestHash(query,body),()->{
      JsonNode index=fetch(write,c,i);
      if(operation.equals("build_ann") && (index.path("ann_strategy").asText().equals("none") || !index.path("state").asText().equals("ready")))
        throw new V2Exception(409,"ann_build_unavailable","Index configuration is not ready for an ANN build");
      var job=jobs.insert(UUID.randomUUID(),c,i,null,"maintenance",mapper.createObjectNode().put("operation",operation));
      ObjectNode response=mapper.createObjectNode().put("id",job.id().toString()).put("kind","maintenance").put("status","queued");
      return new IdempotencyService.Result(202,response,Map.of("Location","/api/v2/jobs/"+job.id()),job.id());
    });
  }
  public JsonNode get(String collection,String id,MultiValueMap<String,String> query) { requireQuery(query,Set.of());UUID c=uuid(collection),i=uuid(id);return reads.execute(tx->fetch(read,c,i)); }
  public PageResponse<JsonNode> list(String collection,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of("limit","cursor"));UUID c=uuid(collection);int limit=100;
    try { if(query.containsKey("limit")) limit=Integer.parseInt(query.getFirst("limit")); } catch(NumberFormatException e) { throw V2Exception.badRequest("Invalid limit"); }
    if(limit<1 || limit>1000) throw V2Exception.badRequest("Invalid limit");String path=path(c);
    var pos=cursors.decode(query.getFirst("cursor"),path,"");if(pos.size()>1) throw V2Exception.badRequest("Invalid cursor");UUID after=pos.isEmpty()?null:uuid(pos.getFirst());int size=limit;
    return reads.execute(tx->{if(!exists(read,"SELECT EXISTS(SELECT 1 FROM dvp.collections WHERE id=?)",c)) throw new V2Exception(404,"collection_not_found","Collection does not exist");
      var ids=read.query("SELECT id FROM dvp.indexations WHERE collection_id=? AND (?::uuid IS NULL OR id>?) ORDER BY id LIMIT ?",(rs,n)->rs.getObject(1,UUID.class),c,after,after,size+1);
      boolean more=ids.size()>size;var page=ids.subList(0,Math.min(size,ids.size())).stream().map(i->fetch(read,c,i)).toList();
      return new PageResponse<>(page,more?cursors.encode(path,"",List.of(ids.get(size-1).toString())):null);});
  }
  private JsonNode fetch(JdbcTemplate jdbc,UUID c,UUID i) {
    return jdbc.query("""
      SELECT i.*,p.dimensions,p.model,p.model_revision,p.execution_mode,
      (SELECT c.reltuples::bigint FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='dvp' AND c.relname=i.vector_table AND c.reltuples>=0) AS vectors_estimated,
      (SELECT GREATEST(s.last_analyze,s.last_autoanalyze) FROM pg_stat_user_tables s
        WHERE s.schemaname='dvp' AND s.relname=i.vector_table) AS measured_at
      FROM dvp.indexations i
      JOIN dvp.embedding_profiles p ON p.id=i.profile_id WHERE i.collection_id=? AND i.id=?
      """,(rs,n)->{try {ObjectNode x=mapper.createObjectNode().put("id",rs.getObject("id").toString()).put("collection_id",c.toString())
          .put("slug",rs.getString("slug")).put("profile_id",rs.getObject("profile_id").toString()).put("metric",rs.getString("metric"))
          .put("ann_strategy",rs.getString("ann_strategy")).put("state",rs.getString("state")).put("ann_state",rs.getString("ann_state"))
          .put("created_at",rs.getTimestamp("created_at").toInstant().toString());
        x.set("selection",mapper.readTree(rs.getString("selection")));x.set("chunking",mapper.readTree(rs.getString("chunking")));
        x.putObject("profile").put("id",rs.getObject("profile_id").toString()).put("model",rs.getString("model")).put("model_revision",rs.getString("model_revision")).put("dimensions",rs.getInt("dimensions")).put("execution_mode",rs.getString("execution_mode"));
        x.putObject("capabilities").put("exact",rs.getString("state").equals("ready")).put("approximate",rs.getString("state").equals("ready") && rs.getString("ann_state").equals("ready"));
        ObjectNode counters=x.putObject("counters").put("is_estimate",true);long estimate=rs.getLong("vectors_estimated");
        if(rs.wasNull())counters.putNull("vectors_estimated");else counters.put("vectors_estimated",estimate);
        var measured=rs.getTimestamp("measured_at");if(measured==null)counters.putNull("measured_at");else counters.put("measured_at",measured.toInstant().toString());
        return (JsonNode)x;}catch(java.io.IOException e){throw new IllegalStateException("Invalid index configuration");}},c,i).stream().findFirst()
      .orElseThrow(()->new V2Exception(404,"indexation_not_found","Index configuration does not exist in collection"));
  }
  static String table(UUID id) { return "vec_"+id.toString().replace("-",""); }
  private static boolean exists(JdbcTemplate j,String sql,UUID id) { return Boolean.TRUE.equals(j.queryForObject(sql,Boolean.class,id)); }
  private static String path(UUID c) {return "/api/v2/collections/"+c+"/indexations";}
  private static UUID uuid(String s) {try {UUID u=UUID.fromString(s);if(!u.toString().equalsIgnoreCase(s))throw new IllegalArgumentException();return u;}catch(RuntimeException e){throw V2Exception.badRequest("Invalid UUID");}}
  private static String text(JsonNode n,String key,String fallback) {JsonNode v=n.get(key);if(v==null)return fallback;if(!v.isTextual())throw V2Exception.badRequest("Invalid "+key);return v.textValue();}
  private static void object(JsonNode n,Set<String> fields) {if(n==null || !n.isObject())throw V2Exception.badRequest("Expected object");n.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw V2Exception.badRequest("Unknown field");});}
  private ObjectNode selection(JsonNode n) {ObjectNode out=mapper.createObjectNode();if(n==null)n=mapper.createObjectNode();object(n,Set.of("kinds","languages"));
    for(String k:List.of("kinds","languages")){var values=out.putArray(k);if(n.has(k)){if(!n.get(k).isArray() || n.get(k).size()>1000)throw V2Exception.badRequest("Invalid selection");for(JsonNode v:n.get(k)){if(!v.isTextual() || v.asText().isBlank() || v.asText().length()>100)throw V2Exception.badRequest("Invalid selection");values.add(v.asText());}}}return out;}
  private ObjectNode chunking(JsonNode n) {if(n==null)n=mapper.createObjectNode().put("mode","none");object(n,Set.of("mode","max_chars","overlap_chars"));String mode=text(n,"mode","none");ObjectNode out=mapper.createObjectNode().put("mode",mode);
    if(mode.equals("none")){if(n.has("max_chars")||n.has("overlap_chars"))throw V2Exception.badRequest("Invalid chunking");return out;}
    if(!mode.equals("characters-v1"))throw V2Exception.badRequest("Invalid chunking mode");int max=integer(n,"max_chars",2000),overlap=integer(n,"overlap_chars",200);
    if(max<1 || overlap<0 || overlap>=max)throw V2Exception.badRequest("Invalid chunking bounds");return out.put("max_chars",max).put("overlap_chars",overlap);}
  private static int integer(JsonNode n,String key,int fallback) {JsonNode v=n.get(key);if(v==null)return fallback;if(!v.isIntegralNumber() || !v.canConvertToInt())throw V2Exception.badRequest("Invalid "+key);return v.intValue();}
  private static void requireQuery(MultiValueMap<String,String> q,Set<String> allowed) {if(!allowed.containsAll(q.keySet()) || q.values().stream().anyMatch(v->v.size()!=1))throw V2Exception.badRequest("Unknown or repeated query parameter");}
}
