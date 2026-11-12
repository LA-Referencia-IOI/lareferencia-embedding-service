package org.lareferencia.embedding.v2.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.lareferencia.embedding.v2.common.*;
import org.lareferencia.embedding.v2.importing.ImportFileRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

@Service
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class JobService {
  public static final String PATH="/api/v2/jobs";
  private final JobRepository jobs;private final JobHandlers handlers;private final IdempotencyService idempotency;
  private final CanonicalJson canonical;private final CursorCodec cursors;private final ObjectMapper mapper;
  private final TransactionTemplate reads;
  private final ImportFileRepository importFiles;
  public JobService(JobRepository jobs,JobHandlers handlers,IdempotencyService idempotency,CanonicalJson canonical,
      CursorCodec cursors,ObjectMapper mapper,@Qualifier("readTransactions") TransactionTemplate reads,ImportFileRepository importFiles) {
    this.jobs=jobs;this.handlers=handlers;this.idempotency=idempotency;this.canonical=canonical;
    this.cursors=cursors;this.mapper=mapper;this.reads=reads;this.importFiles=importFiles;
  }
  /** Create in the same idempotent transaction as its response; only implemented handlers may be queued. */
  public IdempotencyService.Result create(UUID collectionId,UUID indexationId,String kind,JsonNode parameters,
      String key,String scope,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of());
    requireHandler(kind);
    return idempotency.execute("POST "+scope,key,canonical.requestHash(query,parameters),()->{
      var job=jobs.insert(UUID.randomUUID(),collectionId,indexationId,null,kind,parameters);
      return created(job);
    });
  }
  public PageResponse<JobResource> list(MultiValueMap<String,String> query) {
    requireQuery(query,Set.of("limit","cursor"));int limit=100;
    try { if(query.containsKey("limit")) limit=Integer.parseInt(query.getFirst("limit")); }
    catch(NumberFormatException e) { throw V2Exception.badRequest("Invalid limit"); }
    if(limit<1 || limit>1000) throw V2Exception.badRequest("limit must be between 1 and 1000");
    var position=cursors.decode(query.getFirst("cursor"),PATH,"");
    if(position.size()>1) throw V2Exception.badRequest("Invalid cursor position");
    UUID after=position.isEmpty()?null:uuid(position.getFirst());int size=limit;
    return reads.execute(tx->{
      var rows=jobs.listForRead(after,size+1);boolean more=rows.size()>size;
      var page=rows.subList(0,Math.min(size,rows.size())).stream().map(this::resource).toList();
      return new PageResponse<>(page,more?cursors.encode(PATH,"",List.of(page.getLast().id().toString())):null);
    });
  }
  public JobResource get(String id,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of());UUID uuid=uuid(id);
    return reads.execute(tx->resource(jobs.findForRead(uuid).orElseThrow(()->new V2Exception(404,"job_not_found","Job does not exist"))));
  }
  public PageResponse<JobErrorResource> errors(String id,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of("limit","cursor"));UUID uuid=uuid(id);
    int limit=100;
    try { if(query.containsKey("limit")) limit=Integer.parseInt(query.getFirst("limit")); }
    catch(NumberFormatException e) { throw V2Exception.badRequest("Invalid limit"); }
    if(limit<1 || limit>1000) throw V2Exception.badRequest("limit must be between 1 and 1000");
    String scope=PATH+"/"+uuid+"/errors";var keys=cursors.decode(query.getFirst("cursor"),scope,"");
    if(keys.size()>1) throw V2Exception.badRequest("Invalid cursor position");
    String after=keys.isEmpty()?null:keys.getFirst();int size=limit;
    return reads.execute(tx->{
      jobs.findForRead(uuid).orElseThrow(()->new V2Exception(404,"job_not_found","Job does not exist"));
      var rows=jobs.errorsForRead(uuid,after,size+1);boolean more=rows.size()>size;
      var page=rows.subList(0,Math.min(size,rows.size())).stream().map(this::errorResource).toList();
      String next=more?cursors.encode(scope,"",List.of(page.getLast().itemKey())):null;
      return new PageResponse<>(page,next);
    });
  }
  public IdempotencyService.Result retry(String id,String key,MultiValueMap<String,String> query) {
    requireQuery(query,Set.of());UUID parentId=uuid(id);String scope=PATH+"/"+parentId+"/retries";
    JsonNode empty=mapper.createObjectNode();
    return idempotency.execute("POST "+scope,key,canonical.requestHash(query,empty),()->{
      var parent=jobs.lock(parentId);
      if(Set.of("queued","running").contains(parent.status()))
        throw new V2Exception(409,"job_active","An active job cannot be retried");
      requireHandler(parent.kind());
      if("import".equals(parent.kind()) && "file_correction_required".equals(parent.errorCode()))
        throw new V2Exception(409,"file_correction_required","Correct and upload the file again");
      if("import".equals(parent.kind())) importFiles.requireRetainedForRetry(parentId);
      if(jobs.errors(parent.id(),null,1).isEmpty())
        throw new V2Exception(409,"no_retryable_failures","Job has no failed items to retry");
      var child=jobs.insert(UUID.randomUUID(),parent.collectionId(),parent.indexationId(),parent.id(),
          parent.kind(),parent.parameters());
      jobs.initializeRetry(child.id());
      return created(child);
    });
  }
  private IdempotencyService.Result created(JobRepository.Job job) {
    return new IdempotencyService.Result(202,mapper.valueToTree(resource(job)),
        Map.of("Location",PATH+"/"+job.id()),job.id());
  }
  private JobHandler requireHandler(String kind) {
    return handlers.find(kind).orElseThrow(()->new V2Exception(409,"job_kind_unavailable","Job kind is not implemented"));
  }
  private JobResource resource(JobRepository.Job job) {
    JsonNode parameters=handlers.find(job.kind()).map(handler->handler.publicParameters(job.parameters()))
        .orElseGet(mapper::createObjectNode);
    return new JobResource(job.id(),job.kind(),job.status(),job.collectionId(),job.indexationId(),job.parentJobId(),
        parameters,job.counters(),job.createdAt(),job.finishedAt(),job.errorCode());
  }
  private JobErrorResource errorResource(JobRepository.ItemError row) {
    ObjectNode safe=mapper.createObjectNode();
    for(String name:List.of("line","byte_offset","record_ordinal","sequence_id","external_id","text_id","expected_revision"))
      if(row.locator().has(name) && row.locator().get(name).isValueNode()
          && row.locator().get(name).asText().length() <= 512) safe.set(name,row.locator().get(name));
    return new JobErrorResource(row.itemKey(),row.documentId(),safe,row.code(),"Item processing failed",row.attempts());
  }
  private static UUID uuid(String value) {
    try { UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException();return id; }
    catch(IllegalArgumentException e) { throw V2Exception.badRequest("Invalid UUID"); }
  }
  private static void requireQuery(MultiValueMap<String,String> query,Set<String> allowed) {
    if(!allowed.containsAll(query.keySet()) || query.values().stream().anyMatch(v->v.size()!=1))
      throw V2Exception.badRequest("Unknown or repeated query parameter");
  }
}
