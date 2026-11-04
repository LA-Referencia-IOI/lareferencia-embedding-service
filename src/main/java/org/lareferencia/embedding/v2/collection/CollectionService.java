package org.lareferencia.embedding.v2.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.lareferencia.embedding.v2.common.*;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

@Service
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class CollectionService {
  public static final String PATH="/api/v2/collections";
  private final CollectionRepository repository;
  private final IdempotencyService idempotency;
  private final CanonicalJson canonical;
  private final CursorCodec cursors;
  private final ObjectMapper mapper;
  private final TransactionTemplate reads;
  public CollectionService(CollectionRepository repository,IdempotencyService idempotency,CanonicalJson canonical,
      CursorCodec cursors,ObjectMapper mapper,@Qualifier("readTransactions") TransactionTemplate reads) {
    this.repository=repository;this.idempotency=idempotency;this.canonical=canonical;
    this.cursors=cursors;this.mapper=mapper;this.reads=reads;
  }
  public IdempotencyService.Result create(CreateCollectionRequest request,String key,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of());
    if(request.slug()==null || !request.slug().matches("[a-z0-9][a-z0-9_-]{0,99}"))
      throw V2Exception.badRequest("slug must be 1–100 lowercase ASCII letters, digits, hyphens or underscores and begin with a letter or digit");
    String name=request.name();
    if(name==null || name.codePointCount(0,name.length())>200 || !validUnicode(name))
      throw V2Exception.badRequest("name must be a Unicode string of at most 200 code points without NUL");
    return idempotency.execute("POST "+PATH,key,canonical.requestHash(query,request),()->{
      try {
        var resource=repository.insert(UUID.randomUUID(),request.slug(),name);
        return new IdempotencyService.Result(201,mapper.valueToTree(resource),Map.of("Location",PATH+"/"+resource.id()),null);
      } catch(DataIntegrityViolationException e) {
        for(Throwable cause=e;cause!=null;cause=cause.getCause()) {
          if(cause instanceof PSQLException pg && "23505".equals(pg.getSQLState())
              && pg.getServerErrorMessage()!=null && "collections_slug_key".equals(pg.getServerErrorMessage().getConstraint())) {
            throw new V2Exception(409,"collection_exists","Collection slug already exists");
          }
        }
        throw e;
      }
    });
  }
  public CollectionResource get(String id,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of());
    UUID uuid=uuid(id);
    return reads.execute(status->repository.find(uuid)
        .orElseThrow(()->new V2Exception(404,"collection_not_found","Collection does not exist")));
  }
  public PageResponse<CollectionResource> list(MultiValueMap<String,String> query) {
    requireQuery(query,Set.of("limit","cursor"));
    int limit=100;
    try { if(query.containsKey("limit")) limit=Integer.parseInt(query.getFirst("limit")); }
    catch(NumberFormatException e) { throw V2Exception.badRequest("Invalid limit"); }
    if(limit<1 || limit>1000) throw V2Exception.badRequest("limit must be between 1 and 1000");
    var position=cursors.decode(query.getFirst("cursor"),PATH,"");
    if(position.size()>1) throw V2Exception.badRequest("Invalid cursor position");
    UUID after=position.isEmpty()?null:uuid(position.getFirst());
    int pageSize=limit;
    return reads.execute(status->{
      var rows=repository.list(after,pageSize+1);
      boolean more=rows.size()>pageSize;
      var page=List.copyOf(rows.subList(0,Math.min(pageSize,rows.size())));
      String next=more?cursors.encode(PATH,"",List.of(page.getLast().id().toString())):null;
      return new PageResponse<>(page,next);
    });
  }
  private static UUID uuid(String value) {
    try {
      UUID id=UUID.fromString(value);
      if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch(IllegalArgumentException e) { throw V2Exception.badRequest("Invalid UUID"); }
  }
  private static void requireQuery(MultiValueMap<String,String> query,Set<String> allowed) {
    if(!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(v->v.size()!=1))
      throw V2Exception.badRequest("Unknown or repeated query parameter");
  }
  private static boolean validUnicode(String value) {
    for(int i=0;i<value.length();i++) {
      char c=value.charAt(i);
      if(c==0 || Character.isLowSurrogate(c)) return false;
      if(Character.isHighSurrogate(c) && (++i==value.length() || !Character.isLowSurrogate(value.charAt(i)))) return false;
    }
    return true;
  }
}
