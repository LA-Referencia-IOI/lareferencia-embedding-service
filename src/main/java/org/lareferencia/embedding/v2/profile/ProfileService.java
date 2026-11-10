package org.lareferencia.embedding.v2.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.lareferencia.embedding.v2.common.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

@Service
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class ProfileService {
  public static final String PATH="/api/v2/embedding-profiles";
  private final ProfileRepository repository;
  private final ProviderRegistry providers;
  private final CanonicalJson canonical;
  private final IdempotencyService idempotency;
  private final CursorCodec cursors;
  private final ObjectMapper mapper;
  private final TransactionTemplate reads;
  public ProfileService(ProfileRepository repository,ProviderRegistry providers,CanonicalJson canonical,IdempotencyService idempotency,
      CursorCodec cursors,ObjectMapper mapper,@Qualifier("readTransactions") TransactionTemplate reads) {
    this.repository=repository;this.providers=providers;this.canonical=canonical;this.idempotency=idempotency;
    this.cursors=cursors;this.mapper=mapper;this.reads=reads;
  }
  public IdempotencyService.Result create(ProfileRequest raw,String key,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of());ProfileRequest p=normalize(raw);
    var recipe=mapper.createObjectNode().put("model",p.model()).put("model_revision",p.modelRevision())
        .put("dimensions",p.dimensions()).put("execution_mode",p.executionMode());
    if(p.providerRef()==null) recipe.putNull("provider_ref");else recipe.put("provider_ref",p.providerRef());
    recipe.putObject("preparation").put("version",p.preparation().version())
        .put("document_prefix",p.preparation().documentPrefix()).put("query_prefix",p.preparation().queryPrefix());
    String fingerprint=canonical.hash(recipe);
    return idempotency.execute("POST "+PATH,key,canonical.requestHash(query,raw),()->{
      if("http".equals(p.executionMode()) && !providers.contains(p.providerRef()))
        throw new V2Exception(422,"unknown_provider","Provider reference is not configured");
      var created=repository.insert(p,fingerprint);
      if(created.isEmpty()) {
        var existing=repository.duplicate(p.slug(),fingerprint);
        String reference=existing.map(value->PATH+"/"+value.id()).orElse(PATH);
        throw new V2Exception(409,"profile_exists","Profile slug or fingerprint already exists: "+reference);
      }
      var value=created.get();
      return new IdempotencyService.Result(201,mapper.valueToTree(value),Map.of("Location",PATH+"/"+value.id()),null);
    });
  }
  public ProfileRequest normalize(ProfileRequest p) {
    if(p==null || p.slug()==null || !p.slug().matches("[a-z0-9][a-z0-9_-]{0,99}")) throw V2Exception.badRequest("Invalid profile slug");
    string(p.model(),255,"model");string(p.modelRevision(),255,"model_revision");
    if(p.dimensions()==null || p.dimensions()<1 || p.dimensions()>16000) throw V2Exception.badRequest("dimensions must be between 1 and 16000");
    if(!"http".equals(p.executionMode()) && !"import_only".equals(p.executionMode())) throw V2Exception.badRequest("Invalid execution_mode");
    if("http".equals(p.executionMode()) && (p.providerRef()==null || !p.providerRef().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}")))
      throw new V2Exception(422,"unknown_provider","Provider reference is not configured");
    if("import_only".equals(p.executionMode()) && p.providerRef()!=null) throw V2Exception.badRequest("import_only profiles must not specify provider_ref");
    var prep=p.preparation();
    if(prep==null || !("identity-v1".equals(prep.version()) || "legacy-normalizer-v1".equals(prep.version())))
      throw V2Exception.badRequest("Unsupported preparation version");
    String doc=prep.documentPrefix()==null?"":prep.documentPrefix(), query=prep.queryPrefix()==null?"":prep.queryPrefix();
    text(doc,8192,"document_prefix");text(query,8192,"query_prefix");
    return new ProfileRequest(p.slug(),p.model(),p.modelRevision(),p.dimensions(),p.executionMode(),p.providerRef(),new ProfileRequest.Preparation(prep.version(),doc,query));
  }
  private static void string(String value,int max,String name) { if(value==null || value.isBlank()) throw V2Exception.badRequest(name+" must not be blank");text(value,max,name); }
  private static void text(String value,int max,String name) {
    if(value.codePointCount(0,value.length())>max) throw V2Exception.badRequest(name+" exceeds its limit");
    for(int i=0;i<value.length();i++) { char c=value.charAt(i);if(c==0 || Character.isLowSurrogate(c) || (Character.isHighSurrogate(c) && (++i==value.length() || !Character.isLowSurrogate(value.charAt(i))))) throw V2Exception.badRequest(name+" contains invalid Unicode"); }
  }
  public ProfileResource get(String id,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of());UUID uuid=uuid(id);
    return reads.execute(status->repository.find(uuid).orElseThrow(()->new V2Exception(404,"profile_not_found","Profile does not exist")));
  }
  public PageResponse<ProfileResource> list(MultiValueMap<String,String> query) {
    requireQuery(query,Set.of("limit","cursor"));int limit=100;
    try { if(query.containsKey("limit")) limit=Integer.parseInt(query.getFirst("limit")); } catch(NumberFormatException e) { throw V2Exception.badRequest("Invalid limit"); }
    if(limit<1 || limit>1000) throw V2Exception.badRequest("limit must be between 1 and 1000");
    var position=cursors.decode(query.getFirst("cursor"),PATH,"");if(position.size()>1) throw V2Exception.badRequest("Invalid cursor");
    UUID after=position.isEmpty()?null:uuid(position.getFirst());int size=limit;
    return reads.execute(status->{var rows=repository.list(after,size+1);boolean more=rows.size()>size;
      var page=List.copyOf(rows.subList(0,Math.min(size,rows.size())));
      return new PageResponse<>(page,more?cursors.encode(PATH,"",List.of(page.getLast().id().toString())):null);});
  }
  private static UUID uuid(String value) { try { UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();return id; } catch(IllegalArgumentException e) { throw V2Exception.badRequest("Invalid UUID"); } }
  private static void requireQuery(MultiValueMap<String,String> query,Set<String> allowed) {
    if(!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(v->v.size()!=1)) throw V2Exception.badRequest("Unknown or repeated query parameter");
  }
}
