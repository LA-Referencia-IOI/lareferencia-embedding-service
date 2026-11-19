package org.lareferencia.embedding.v2.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.lareferencia.embedding.v2.common.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

@Service
@ConditionalOnProperty(name="app.dvp.enabled", havingValue="true")
public class VectorRetrievalService {
  private final JdbcTemplate jdbc; private final TransactionTemplate tx; private final CursorCodec cursors; private final ObjectMapper mapper;
  public VectorRetrievalService(@Qualifier("readJdbc") JdbcTemplate jdbc,@Qualifier("readTransactions") TransactionTemplate tx,CursorCodec cursors,ObjectMapper mapper) {
    this.jdbc=jdbc;this.tx=tx;this.cursors=cursors;this.mapper=mapper;
  }
  private record Config(UUID id,String model,String table,String state) {}
  private record Unit(UUID id,String vector) {}

  public Map<String,Object> get(String collection,String indexation,String document,boolean external,MultiValueMap<String,String> query) {
    UUID i=uuid(indexation), d=external?null:uuid(document);
    Set<String> allowed=external?Set.of("external_id","collection_id","limit","cursor"):Set.of("collection_id","limit","cursor");
    for(var entry:query.entrySet()) if(!allowed.contains(entry.getKey()) || entry.getValue().size()!=1) throw V2Exception.badRequest("Invalid query parameter");
    if(collection!=null && query.containsKey("collection_id"))throw V2Exception.badRequest("Collection is already specified in the path");
    UUID c=collection!=null?uuid(collection):query.containsKey("collection_id")?uuid(query.getFirst("collection_id")):null;
    String externalId=external?query.getFirst("external_id"):null;
    if(external && (externalId==null || externalId.isBlank() || externalId.length()>512)) throw V2Exception.badRequest("Invalid external_id");
    Integer limit=null;
    if(query.containsKey("limit")) {
      try { limit=Integer.parseInt(query.getFirst("limit")); } catch(NumberFormatException e) { throw V2Exception.badRequest("Invalid limit"); }
      if(limit<1||limit>1000)throw V2Exception.badRequest("Invalid limit");
    }
    if(query.containsKey("cursor") && limit==null)throw V2Exception.badRequest("A limit is required with cursor");
    String scope="document-vectors:"+c+":"+i+":"+(external?"external:"+externalId:"id:"+d);
    List<String> keys=cursors.decode(query.getFirst("cursor"),scope,"");
    UUID after=null;
    if(!keys.isEmpty()) {
      if(keys.size()!=2 || !uuid(keys.get(0)).equals(i))throw V2Exception.badRequest("Invalid cursor");
      after=uuid(keys.get(1));
    }
    Integer pageSize=limit; UUID afterUnit=after;
    return tx.execute(status->read(c,i,d,externalId,pageSize,afterUnit,scope));
  }
  private Map<String,Object> read(UUID collection,UUID indexation,UUID document,String external,Integer limit,UUID after,String scope) {
    var docs=jdbc.query("SELECT d.id FROM dvp.documents d JOIN dvp.collection_indexations ci ON ci.collection_id=d.collection_id AND ci.indexation_id=? WHERE (?::uuid IS NULL OR d.collection_id=?) AND "+(document==null?"d.external_id=?":"d.id=?")+" LIMIT 2",
      (rs,n)->rs.getObject(1,UUID.class),indexation,collection,collection,document==null?external:document);
    if(docs.isEmpty())throw new V2Exception(404,"not_found","Resource not found");
    if(docs.size()>1)throw new V2Exception(409,"ambiguous_external_id","External ID exists in multiple collections; specify collection_id");
    var configs=jdbc.query("SELECT i.id,p.model,i.vector_table,i.state FROM dvp.indexations i JOIN dvp.embedding_profiles p ON p.id=i.profile_id WHERE i.id=?",
      (rs,n)->new Config(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)),indexation);
    if(configs.isEmpty())throw new V2Exception(404,"not_found","Resource not found");
    Config config=configs.getFirst();
    if(!config.state().equals("ready"))throw new V2Exception(409,"indexation_unavailable","Index configuration is not ready");
    if(!config.table().equals("vec_"+config.id().toString().replace("-","")))throw new IllegalStateException("Invalid vector table identity");
    String sql="SELECT u.id,v.embedding::text FROM dvp.vector_units u JOIN dvp."+config.table()+" v ON v.unit_id=u.id AND v.document_id=u.document_id AND v.indexation_id=u.indexation_id WHERE u.document_id=? AND u.indexation_id=?"+(after==null?"":" AND u.id>?")+" ORDER BY u.id"+(limit==null?"":" LIMIT ?");
    List<Object> args=new ArrayList<>(List.of(docs.getFirst(),indexation));
    if(after!=null)args.add(after);
    if(limit!=null)args.add(limit+1);
    var units=jdbc.query(sql,(rs,n)->new Unit(rs.getObject(1,UUID.class),rs.getString(2)),args.toArray());
    boolean more=limit!=null && units.size()>limit;
    int count=more?limit:units.size();
    List<Map<String,Object>> data=new ArrayList<>(count);
    for(int n=0;n<count;n++) {
      try {data.add(Map.of("object","embedding","embedding",mapper.readTree(units.get(n).vector()),"index",n));}
      catch(java.io.IOException e) {throw new IllegalStateException("Invalid stored JSON",e);}
    }
    Map<String,Object> result=new LinkedHashMap<>();result.put("object","list");result.put("data",data);result.put("model",config.model());
    if(limit!=null)result.put("next_cursor",more?cursors.encode(scope,"",List.of(indexation.toString(),units.get(count-1).id().toString())):null);
    return result;
  }
  private static UUID uuid(String s) {try {UUID u=UUID.fromString(s);if(!u.toString().equalsIgnoreCase(s))throw new IllegalArgumentException();return u;}catch(RuntimeException e){throw V2Exception.badRequest("Invalid UUID");}}
}
