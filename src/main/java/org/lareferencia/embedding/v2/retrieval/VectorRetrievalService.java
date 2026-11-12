package org.lareferencia.embedding.v2.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
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
  private record Document(UUID id,String external,long revision,String metadata) {}
  private record Config(UUID id,UUID profile,String model,String revision,int dimensions,String table,String state,JsonNode selection) {}
  private record Text(UUID id,String key,String kind,String language,String content,long revision) {}
  private record Unit(UUID id,UUID text,long sourceRevision,int ordinal,int start,int end,String origin,String vector) {}
  public Map<String,Object> get(String collection,String indexation,String document,boolean external,MultiValueMap<String,String> query) {
    UUID c=uuid(collection), i=indexation==null?null:uuid(indexation), d=external?null:uuid(document);
    Set<String> allowed=external?Set.of("external_id","limit","cursor","include"):Set.of("limit","cursor","include");
    for(var entry:query.entrySet()) if(!allowed.contains(entry.getKey()) || entry.getValue().size()!=1) throw V2Exception.badRequest("Invalid query parameter");
    String externalId=external?query.getFirst("external_id"):null;
    if(external && (externalId==null || externalId.isBlank() || externalId.length()>512)) throw V2Exception.badRequest("Invalid external_id");
    int limit=100;
    try {if(query.containsKey("limit"))limit=Integer.parseInt(query.getFirst("limit"));} catch(NumberFormatException e) {throw V2Exception.badRequest("Invalid limit");}
    if(limit<1||limit>1000)throw V2Exception.badRequest("Invalid limit");
    Set<String> includes=new HashSet<>();String include=query.getFirst("include");
    if(include!=null) for(String value:include.split(",",-1)) if(!Set.of("texts","metadata").contains(value)||!includes.add(value))throw V2Exception.badRequest("Invalid include");
    String scope="document-vectors:"+c+":"+(i==null?"all":i)+":"+(external?"external:"+externalId:"id:"+d);
    String selection=includes.stream().sorted().reduce((a,b)->a+","+b).orElse("");
    List<String> keys=cursors.decode(query.getFirst("cursor"),scope,selection);
    UUID afterIndex=null,afterUnit=null;
    if(!keys.isEmpty()) {if(keys.size()!=2)throw V2Exception.badRequest("Invalid cursor");afterIndex=uuid(keys.get(0));afterUnit=uuid(keys.get(1));}
    int pageSize=limit; UUID ai=afterIndex,au=afterUnit;
    return tx.execute(status->read(c,i,d,externalId,includes,pageSize,ai,au,scope,selection));
  }
  private Map<String,Object> read(UUID collection,UUID indexation,UUID document,String external,Set<String> includes,int limit,UUID afterIndex,UUID afterUnit,String scope,String selection) {
    var docs=jdbc.query("SELECT id,external_id,revision,metadata::text FROM dvp.documents WHERE collection_id=? AND "+(document==null?"external_id=?":"id=?"),
      (rs,n)->new Document(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getString(4)),collection,document==null?external:document);
    if(docs.isEmpty())throw new V2Exception(404,"not_found","Resource not found");
    Document doc=docs.getFirst();
    var configs=jdbc.query("SELECT i.id,i.profile_id,p.model,p.model_revision,p.dimensions,i.vector_table,i.state,i.selection::text FROM dvp.indexations i JOIN dvp.embedding_profiles p ON p.id=i.profile_id WHERE i.collection_id=?"+(indexation==null?"":" AND i.id=?")+" ORDER BY i.id",(rs,n)->new Config(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6),rs.getString(7),json(rs.getString(8))),indexation==null?new Object[]{collection}:new Object[]{collection,indexation});
    if(indexation!=null && configs.isEmpty())throw new V2Exception(404,"not_found","Resource not found");
    if(indexation!=null && !configs.getFirst().state().equals("ready"))throw new V2Exception(409,"indexation_unavailable","Index configuration is not ready");
    var texts=jdbc.query("SELECT id,text_key,kind,language,"+(includes.contains("texts")?"content":"NULL::text")+",revision FROM dvp.source_texts WHERE document_id=?",(rs,n)->new Text(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6)),doc.id());
    Map<UUID,Text> textById=new HashMap<>();texts.forEach(t->textById.put(t.id(),t));
    Map<String,Object> descriptors=new LinkedHashMap<>();List<UUID> unavailable=new ArrayList<>();List<Map<String,Object>> items=new ArrayList<>();boolean more=false;UUID lastIndex=null,lastUnit=null;
    for(Config config:configs) {
      if(!config.state().equals("ready")) {unavailable.add(config.id());continue;}
      if(!config.table().equals("vec_"+config.id().toString().replace("-",""))) throw new IllegalStateException("Invalid vector table identity");
      String table="dvp."+config.table();
      List<Text> eligible=texts.stream().filter(t->matches(config.selection().path("kinds"),t.kind()) && matches(config.selection().path("languages"),t.language())).toList();
      String joins=" FROM dvp.vector_units u JOIN dvp.source_texts s ON s.id=u.source_text_id AND s.document_id=u.document_id AND s.revision=u.source_revision JOIN dvp.indexations i ON i.id=u.indexation_id LEFT JOIN "+table+" v ON v.unit_id=u.id AND v.document_id=u.document_id AND v.source_text_id=u.source_text_id AND v.source_revision=u.source_revision AND v.indexation_id=u.indexation_id WHERE u.document_id=? AND u.collection_id=? AND u.indexation_id=? AND (COALESCE(jsonb_array_length(i.selection->'kinds'),0)=0 OR jsonb_exists(i.selection->'kinds',s.kind)) AND (COALESCE(jsonb_array_length(i.selection->'languages'),0)=0 OR jsonb_exists(i.selection->'languages',s.language))";
      long[] counts=jdbc.queryForObject("SELECT count(*),count(v.unit_id),count(DISTINCT u.source_text_id)"+joins,(rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3)},doc.id(),collection,config.id());
      long preparedUnits=counts[0],current=counts[1],missing=preparedUnits-current,unprepared=eligible.size()-counts[2];
      String state=eligible.isEmpty()?"not_applicable":current==0?"pending":(unprepared>0||missing>0)?"partial":"complete";
      var coverage=Map.of("state",state,"eligible_texts",eligible.size(),"unprepared_texts",unprepared,"prepared_units",preparedUnits,"current_vectors",current,"missing_vectors",missing);
      descriptors.put(config.id().toString(),Map.of("profile_id",config.profile(),"model",config.model(),"model_revision",config.revision(),"dimensions",config.dimensions(),"coverage",coverage));
      if(afterIndex!=null && compare(config.id(),afterIndex)<0) continue;
      UUID start=afterIndex!=null && config.id().equals(afterIndex)?afterUnit:null;
      var units=jdbc.query("SELECT u.id,u.source_text_id,u.source_revision,u.ordinal,u.start_cp,u.end_cp,v.origin,v.embedding::text"+joins+" AND v.unit_id IS NOT NULL"+(start==null?"":" AND u.id>?")+" ORDER BY u.id LIMIT ?",(rs,n)->new Unit(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getString(7),rs.getString(8)),start==null?new Object[]{doc.id(),collection,config.id(),limit-items.size()+1}:new Object[]{doc.id(),collection,config.id(),start,limit-items.size()+1});
      for(Unit u:units) {
        if(items.size()==limit) {more=true;break;}
        Text t=textById.get(u.text());Map<String,Object> item=new LinkedHashMap<>();
        item.put("indexation_id",config.id());item.put("profile_id",config.profile());item.put("unit_id",u.id());item.put("text_id",t.key());item.put("source_revision",u.sourceRevision());item.put("ordinal",u.ordinal());item.put("start_cp",u.start());item.put("end_cp",u.end());item.put("origin",u.origin());item.put("embedding",json(u.vector()));
        if(includes.contains("texts")) {int textStart=t.content().offsetByCodePoints(0,u.start()),end=t.content().offsetByCodePoints(0,u.end());item.put("text",t.content().substring(textStart,end));var source=new LinkedHashMap<String,Object>();source.put("text_id",t.key());source.put("kind",t.kind());source.put("language",t.language());source.put("content",t.content());source.put("revision",t.revision());item.put("source_text",source);}
        items.add(item);lastIndex=config.id();lastUnit=u.id();
      }
    }
    Map<String,Object> documentBody=new LinkedHashMap<>();documentBody.put("id",doc.id());documentBody.put("external_id",doc.external());documentBody.put("revision",doc.revision());if(includes.contains("metadata"))documentBody.put("metadata",json(doc.metadata()));
    Map<String,Object> result=new LinkedHashMap<>();result.put("document",documentBody);result.put("indexations",descriptors);result.put("items",items);result.put("next_cursor",more?cursors.encode(scope,selection,List.of(lastIndex.toString(),lastUnit.toString())):null);if(indexation==null)result.put("unavailable_indexation_ids",unavailable);return result;
  }
  private static boolean matches(JsonNode array,String value) {if(array.isMissingNode()||array.isEmpty())return true;for(JsonNode v:array)if(v.isTextual()&&v.asText().equals(value))return true;return false;}
  private JsonNode json(String s) {try{return mapper.readTree(s);}catch(java.io.IOException e){throw new IllegalStateException("Invalid stored JSON");}}
  private static UUID uuid(String s) {try {UUID u=UUID.fromString(s);if(!u.toString().equalsIgnoreCase(s))throw new IllegalArgumentException();return u;}catch(RuntimeException e){throw V2Exception.badRequest("Invalid UUID");}}
  private static int compare(UUID a,UUID b) {return a.toString().compareTo(b.toString());}
}
