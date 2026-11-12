package org.lareferencia.embedding.v2.preparation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.lareferencia.embedding.v2.common.*;
import org.lareferencia.embedding.v2.job.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class PreparationJobService {
  private final JdbcTemplate write;private final IdempotencyService idempotency;private final CanonicalJson canonical;
  private final JobRepository jobs;private final ObjectMapper mapper;
  public PreparationJobService(@Qualifier("jdbcTemplate") JdbcTemplate write,IdempotencyService idempotency,
      CanonicalJson canonical,JobRepository jobs,ObjectMapper mapper) {
    this.write=write;this.idempotency=idempotency;this.canonical=canonical;this.jobs=jobs;this.mapper=mapper;
  }
  public IdempotencyService.Result create(String collection,String indexation,JsonNode raw,String key,MultiValueMap<String,String> query) {
    return create(collection,indexation,raw,key,query,"prepare");
  }
  public IdempotencyService.Result create(String collection,String indexation,JsonNode raw,String key,MultiValueMap<String,String> query,String kind) {
    if(!Set.of("prepare","generate").contains(kind)) throw new IllegalArgumentException("Invalid job kind");
    if(!query.isEmpty()) throw V2Exception.badRequest("Unknown query parameter");
    UUID c=uuid(collection),i=uuid(indexation);JsonNode body=raw==null?mapper.createObjectNode():raw;
    if(!body.isObject()) throw V2Exception.badRequest("Expected an object");
    body.fieldNames().forEachRemaining(name->{if(!name.equals("document_ids")) throw V2Exception.badRequest("Unknown field");});
    JsonNode ids=body.get("document_ids");var unique=new LinkedHashSet<UUID>();
    if(ids!=null) {
      if(!ids.isArray() || ids.size()>1000) throw V2Exception.badRequest("document_ids must contain at most 1000 UUIDs");
      for(JsonNode id:ids) {if(!id.isTextual() || !unique.add(uuid(id.textValue()))) throw V2Exception.badRequest("Invalid or repeated document ID");}
    }
    var parameters=mapper.createObjectNode();
    if(ids!=null) {var normalized=parameters.putArray("document_ids");unique.forEach(id->normalized.add(id.toString()));}
    String route="/api/v2/collections/"+c+"/indexations/"+i+(kind.equals("prepare")?"/preparation-jobs":"/generation-jobs");
    return idempotency.execute("POST "+route,key,canonical.requestHash(query,body),()->{
      var state=write.query("SELECT i.state,p.execution_mode FROM dvp.indexations i JOIN dvp.embedding_profiles p ON p.id=i.profile_id WHERE i.id=? AND i.collection_id=?",(rs,n)->Map.entry(rs.getString(1),rs.getString(2)),i,c);
      if(state.isEmpty()) throw new V2Exception(404,"indexation_not_found","Index configuration does not exist in collection");
      if(!state.getFirst().getKey().equals("ready")) throw new V2Exception(409,"indexation_unavailable","Index configuration is not ready");
      if(kind.equals("generate") && !state.getFirst().getValue().equals("http")) throw new V2Exception(409,"profile_not_executable","Profile has no inference capability");
      if(!unique.isEmpty()) {
        Integer count=write.queryForObject("""
            SELECT count(*) FROM dvp.documents d JOIN jsonb_array_elements_text(?::jsonb) ids(value) ON d.id::text=ids.value
            WHERE d.collection_id=?
            """,Integer.class,parameters.path("document_ids").toString(),c);
        if(count==null || count!=unique.size()) throw new V2Exception(404,"document_not_found","One or more documents do not exist in the collection");
      }
      var job=jobs.insert(UUID.randomUUID(),c,i,null,kind,parameters);
      var result=new JobResource(job.id(),job.kind(),job.status(),c,i,null,parameters,job.counters(),job.createdAt(),null,null);
      return new IdempotencyService.Result(202,mapper.valueToTree(result),Map.of("Location","/api/v2/jobs/"+job.id()),job.id());
    });
  }
  private static UUID uuid(String value) {
    try {UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();return id;}
    catch(RuntimeException e) {throw V2Exception.badRequest("Invalid UUID");}
  }
}
