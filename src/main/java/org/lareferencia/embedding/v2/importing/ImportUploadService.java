package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;
import org.lareferencia.embedding.v2.common.*;
import org.lareferencia.embedding.v2.job.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/** Stores the complete file before registering the root job. */
@Service
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class ImportUploadService {
  private final DurableImportStore files;private final IdempotencyService idempotency;private final CanonicalJson canonical;
  private final JobRepository jobs;private final JdbcTemplate jdbc;private final ObjectMapper mapper;
  public ImportUploadService(DurableImportStore files,IdempotencyService idempotency,CanonicalJson canonical,
      JobRepository jobs,@Qualifier("jdbcTemplate") JdbcTemplate jdbc,ObjectMapper mapper) {
    this.files=files;this.idempotency=idempotency;this.canonical=canonical;this.jobs=jobs;this.jdbc=jdbc;this.mapper=mapper;
  }
  public IdempotencyService.Result upload(UUID collection,InputStream input,JsonNode options,String key,MultiValueMap<String,String> query) {
    Objects.requireNonNull(collection);Objects.requireNonNull(input);
    if(key==null || key.isBlank() || key.codePointCount(0,key.length())>200 || key.indexOf(0)>=0)
      throw V2Exception.badRequest("A nonblank Idempotency-Key of at most 200 characters is required");
    if(!query.isEmpty())throw V2Exception.badRequest("Unknown query parameter");
    if(options==null || !options.isObject())throw V2Exception.badRequest("Import options must be an object");
    options.fieldNames().forEachRemaining(field->{if(!Set.of("format","mode").contains(field))throw V2Exception.badRequest("Unknown import option");});
    JsonNode formatNode=options.get("format"),modeNode=options.get("mode");
    if(formatNode==null || !formatNode.isTextual() || !Set.of("json","jsonl").contains(formatNode.textValue())
        || modeNode==null || !modeNode.isTextual() || !Set.of("merge","replace").contains(modeNode.textValue()))
      throw V2Exception.badRequest("Invalid import format or mode");
    var normalized=mapper.createObjectNode().put("format",formatNode.textValue()).put("mode",modeNode.textValue());
    UUID proposedJob=UUID.randomUUID();String scope="POST /api/v2/collections/"+collection+"/imports";
    try(var stored=files.store(input)) {
      IdempotencyService.Result result;
      try {result=idempotency.execute(scope,key,canonical.fileHash(normalized,stored.sha256()),()->{
      var job=jobs.insert(proposedJob,collection,null,null,"import",normalized);
      jdbc.update("INSERT INTO dvp.import_files(job_id,storage_key,sha256,format,byte_size) VALUES (?,?,?,?,?)",
          job.id(),stored.key(),stored.sha256(),formatNode.textValue(),stored.byteSize());
      var publicJob=new JobResource(job.id(),job.kind(),job.status(),job.collectionId(),null,null,normalized,job.counters(),job.createdAt(),null,null);
      return new IdempotencyService.Result(202,mapper.valueToTree(publicJob),Map.of("Location","/api/v2/jobs/"+job.id()),job.id());
      });} catch(V2Exception e) {
        if(e.code().equals("idempotency_conflict") || e.code().equals("idempotency_in_progress"))files.discardRedundant(stored);
        throw e;
      }
    // Delete only after a successful replay proves that this newly uploaded file is not referenced.
    // On uncertain database failure retain it for reconciliation rather than risk deleting committed data.
      if(!proposedJob.equals(result.jobId())) files.discardRedundant(stored);
      return result;
    }
  }
}
