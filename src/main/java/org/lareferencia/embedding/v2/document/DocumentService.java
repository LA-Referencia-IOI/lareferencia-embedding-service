package org.lareferencia.embedding.v2.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.lareferencia.embedding.v2.common.*;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

@Service
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class DocumentService {
  private final DocumentRepository repository;
  private final DocumentValidation validation;
  private final DocumentFingerprint fingerprint;
  private final IdempotencyService idempotency;
  private final CursorCodec cursors;
  private final ObjectMapper mapper;
  private final TransactionTemplate reads;
  private final TransactionTemplate writes;

  public DocumentService(DocumentRepository repository,DocumentValidation validation,DocumentFingerprint fingerprint,
      IdempotencyService idempotency,CursorCodec cursors,ObjectMapper mapper,
      @Qualifier("readTransactions") TransactionTemplate reads,@Qualifier("writeTransactions") TransactionTemplate writes) {
    this.repository=repository;this.validation=validation;this.fingerprint=fingerprint;this.idempotency=idempotency;
    this.cursors=cursors;this.mapper=mapper;this.reads=reads;this.writes=writes;
  }

  public IdempotencyService.Result write(String collection,String method,MultiValueMap<String,String> query,
      DocumentWriteRequest request,String ifMatch,String key) {
    UUID collectionId=uuid(collection);
    String externalId=required(query,"external_id",Set.of("external_id"));
    validation.externalId(externalId);
    boolean replace=method.equals("PUT");
    if(!replace && !method.equals("PATCH")) throw new IllegalArgumentException("Unsupported document write method");
    validation.write(request,replace);
    Long expected=Revisions.ifMatch(ifMatch);
    String path=base(collectionId)+"/by-external-id";
    Supplier<IdempotencyService.Result> action=()->upsert(collectionId,externalId,request,replace,expected);
    String hash=key==null?null:fingerprint.hash(query,request,expected);
    return atomic(method+" "+path,key,hash,action);
  }

  /** Apply an import record within its caller's fenced write transaction. */
  public IdempotencyService.Result upsertInTransaction(UUID collectionId,String externalId,DocumentWriteRequest request,
      boolean replace,Long expectedRevision) {
    if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
        || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("An active write transaction is required");
    validation.externalId(externalId);validation.write(request,replace);
    if(expectedRevision!=null && expectedRevision<1) throw V2Exception.badRequest("expected_revision must be positive");
    return upsert(collectionId,externalId,request,replace,expectedRevision);
  }

  private IdempotencyService.Result upsert(UUID collectionId,String externalId,DocumentWriteRequest request,
      boolean replace,Long expected) {
    requireCollection(collectionId,true);
    DocumentRepository.DocumentRow document;
    boolean created=false;
    if(expected!=null) {
      var found=repository.lockByExternal(collectionId,externalId);
      Revisions.requireMatch(expected,found.map(DocumentRepository.DocumentRow::revision).orElse(null));
      document=found.orElseThrow();
    } else {
      document=null;
      // A concurrent delete can remove the conflicting row before SELECT FOR UPDATE.
      // Retry the insert/lock sequence in the same READ COMMITTED transaction.
      for(int attempt=0;attempt<8;attempt++) {
        boolean inserted=repository.insertIfAbsent(UUID.randomUUID(),collectionId,externalId);
        var found=repository.lockByExternal(collectionId,externalId);
        if(found.isPresent()) { document=found.get(); created=inserted; break; }
      }
      if(document==null) throw new V2Exception(503,"document_busy","Document changed concurrently; retry the request");
    }

    List<DocumentRepository.TextRow> oldTexts=repository.texts(document.id(),true);
    Map<String,DocumentRepository.TextRow> existing=new LinkedHashMap<>();
    oldTexts.forEach(text->existing.put(text.textId(),text));
    Map<String,SourceTextInput> desired=new LinkedHashMap<>();
    if(!replace) oldTexts.forEach(text->desired.put(text.textId(),text.asInput()));
    if(request.hasTexts()) request.texts().forEach(text->desired.put(text.textId(),text));
    JsonNode metadata=request.hasMetadata()?request.metadata():document.metadata();
    validation.snapshot(metadata,desired.values());
    String metadataJson=toJson(metadata);
    boolean metadataChanged=request.hasMetadata() && repository.metadataDiffers(document.id(),metadataJson);
    boolean textsChanged=false;
    for(var old:oldTexts) {
      SourceTextInput wanted=desired.get(old.textId());
      if(wanted==null) { repository.deleteText(old.id());textsChanged=true; }
      else if(!sameText(old,wanted)) { repository.updateText(old,wanted,sha256(wanted.content()));textsChanged=true; }
    }
    for(var text:desired.values()) {
      if(!existing.containsKey(text.textId())) { repository.insertText(document.id(),text,sha256(text.content()));textsChanged=true; }
    }
    boolean increment=!created && (metadataChanged || textsChanged);
    if(metadataChanged || increment) repository.updateDocument(document.id(),metadataJson,increment);
    var current=repository.findById(collectionId,document.id(),true).orElseThrow();
    var resource=resource(current,true);
    var headers=new LinkedHashMap<String,String>();
    headers.put("ETag",Revisions.etag(current.revision()));
    if(created) headers.put("Location",base(collectionId)+"/"+current.id());
    return new IdempotencyService.Result(created?201:200,mapper.valueToTree(resource),headers,null);
  }

  public DocumentResource getByExternal(String collection,MultiValueMap<String,String> query) {
    UUID c=uuid(collection);String external=required(query,"external_id",Set.of("external_id"));validation.externalId(external);
    return read(()->{requireCollection(c,false);return resource(repository.findByExternal(c,external)
        .orElseThrow(DocumentService::notFound),false);});
  }
  public DocumentResource getById(String collection,String id,MultiValueMap<String,String> query) {
    UUID c=uuid(collection),doc=uuid(id);requireQuery(query,Set.of());
    return read(()->{requireCollection(c,false);return resource(repository.findById(c,doc,false)
        .orElseThrow(DocumentService::notFound),false);});
  }
  public PageResponse<DocumentSummary> list(String collection,MultiValueMap<String,String> query) {
    UUID c=uuid(collection);requireQuery(query,Set.of("limit","cursor","include"));
    int limit=100;
    try { if(query.containsKey("limit")) limit=Integer.parseInt(query.getFirst("limit")); }
    catch(NumberFormatException e) { throw V2Exception.badRequest("Invalid limit"); }
    if(limit<1 || limit>1000) throw V2Exception.badRequest("limit must be between 1 and 1000");
    String include=query.getFirst("include");
    if(include!=null && !include.isEmpty() && !include.equals("metadata"))
      throw V2Exception.badRequest("Only include=metadata is supported for document listings");
    boolean metadata="metadata".equals(include);
    String path=base(c),selection=metadata?"metadata":"";
    var position=cursors.decode(query.getFirst("cursor"),path,selection);
    if(position.size()>1) throw V2Exception.badRequest("Invalid cursor position");
    Long after=null;
    if(!position.isEmpty()) {
      try {
        after=Long.parseLong(position.getFirst());
        if(after<=0 || !after.toString().equals(position.getFirst())) throw new NumberFormatException();
      } catch(NumberFormatException e) { throw V2Exception.badRequest("Invalid cursor position"); }
    }
    int pageSize=limit;Long cursor=after;
    return read(()->{
      requireCollection(c,false);
      var rows=repository.list(c,cursor,pageSize+1,metadata);
      boolean more=rows.size()>pageSize;
      var selected=rows.subList(0,Math.min(pageSize,rows.size()));
      var page=selected.stream().map(row->new DocumentSummary(row.id(),row.externalId(),row.collectionId(),
          row.revision(),metadata?row.metadata():null,row.createdAt(),row.updatedAt())).toList();
      String next=more?cursors.encode(path,selection,List.of(Long.toString(selected.getLast().sequenceId()))):null;
      return new PageResponse<>(page,next);
    });
  }

  public IdempotencyService.Result delete(String collection,String id,MultiValueMap<String,String> query,
      String textId,String ifMatch,String key) {
    UUID c=uuid(collection),doc=uuid(id);
    boolean textRoute=textId!=null;
    requireQuery(query,textRoute?Set.of("text_id"):Set.of());
    if(textRoute) { textId=required(query,"text_id",Set.of("text_id"));validation.textId(textId); }
    Long expected=Revisions.ifMatch(ifMatch);
    String path=base(c)+"/"+doc+(textRoute?"/texts":"");
    String selectedText=textId;
    Supplier<IdempotencyService.Result> action=()->{
      requireCollection(c,true);
      var found=repository.lockById(c,doc);
      if(found.isEmpty() && expected!=null) Revisions.requireMatch(expected,null);
      var document=found.orElseThrow(DocumentService::notFound);
      Revisions.requireMatch(expected,document.revision());
      var headers=new LinkedHashMap<String,String>();
      if(selectedText==null) repository.deleteDocument(doc);
      else {
        var text=repository.texts(doc,true).stream().filter(t->t.textId().equals(selectedText)).findFirst()
            .orElseThrow(()->new V2Exception(404,"text_not_found","Source text does not exist"));
        repository.deleteText(text.id());
        repository.updateDocument(doc,toJson(document.metadata()),true);
        headers.put("ETag",Revisions.etag(document.revision()+1));
      }
      return new IdempotencyService.Result(204,NullNode.instance,headers,null);
    };
    String hash=key==null?null:fingerprint.hash(query,NullNode.instance,expected);
    return atomic("DELETE "+path,key,hash,action);
  }

  private IdempotencyService.Result atomic(String scope,String key,String hash,Supplier<IdempotencyService.Result> action) {
    try {
      Supplier<IdempotencyService.Result> safe=()->safeDatabase(action);
      return key==null?writes.execute(status->safe.get()):idempotency.execute(scope,key,hash,safe);
    } catch(DataAccessException e) { throw databaseError(e); }
  }
  private <T> T read(Supplier<T> action) {
    try { return reads.execute(status->safeDatabase(action)); }
    catch(DataAccessException e) { throw databaseError(e); }
  }
  private <T> T safeDatabase(Supplier<T> action) {
    try { return action.get(); }
    catch(DataAccessException e) { throw databaseError(e); }
  }
  private V2Exception databaseError(DataAccessException e) {
      for(Throwable cause=e;cause!=null;cause=cause.getCause()) {
        if(cause instanceof PSQLException pg && pg.getSQLState()!=null && Set.of("22003","22P05","22021").contains(pg.getSQLState()))
          return new V2Exception(422,"invalid_metadata","Metadata cannot be represented by the database");
      }
      return new V2Exception(503,"database_unavailable","Database operation unavailable");
  }
  private DocumentResource resource(DocumentRepository.DocumentRow doc,boolean forWrite) {
    var texts=repository.texts(doc.id(),forWrite).stream().map(DocumentRepository.TextRow::resource).toList();
    return new DocumentResource(doc.id(),doc.externalId(),doc.collectionId(),doc.revision(),doc.metadata(),
        texts,doc.createdAt(),doc.updatedAt());
  }
  private boolean sameText(DocumentRepository.TextRow old,SourceTextInput text) {
    return Objects.equals(old.kind(),text.kind()) && Objects.equals(old.language(),text.language())
        && Objects.equals(old.content(),text.content());
  }
  private void requireCollection(UUID id,boolean forWrite) {
    if(!repository.collectionExists(id,forWrite)) throw new V2Exception(404,"collection_not_found","Collection does not exist");
  }
  private static V2Exception notFound() { return new V2Exception(404,"document_not_found","Document does not exist"); }
  private String toJson(JsonNode value) {
    try { return mapper.writeValueAsString(value); }
    catch(java.io.IOException e) { throw V2Exception.badRequest("Invalid metadata"); }
  }
  private static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  private static String base(UUID collection) { return "/api/v2/collections/"+collection+"/documents"; }
  private static UUID uuid(String value) {
    try {
      UUID id=UUID.fromString(value);
      if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch(IllegalArgumentException e) { throw V2Exception.badRequest("Invalid UUID"); }
  }
  private static String required(MultiValueMap<String,String> query,String name,Set<String> allowed) {
    requireQuery(query,allowed);
    String value=query.getFirst(name);
    if(value==null) throw V2Exception.badRequest(name+" query parameter is required");
    return value;
  }
  private static void requireQuery(MultiValueMap<String,String> query,Set<String> allowed) {
    if(!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(v->v.size()!=1))
      throw V2Exception.badRequest("Unknown or repeated query parameter");
  }
}
