package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.job.JobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Bounded ordered JSONL processing; wiring into an import worker is intentionally separate. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class JsonlImportProcessor {
  private final JobRepository jobs;private final ImportRecordWriter writer;private final ObjectMapper mapper;private final DvpProperties properties;
  public JsonlImportProcessor(JobRepository jobs,ImportRecordWriter writer,ObjectMapper mapper,DvpProperties properties) {
    this.jobs=jobs;this.writer=writer;this.mapper=mapper;this.properties=properties;
  }
  public int process(JobRepository.Lease lease,InputStream input) throws IOException {
    var job=jobs.find(lease.jobId()).orElseThrow(JobRepository.LeaseLostException::new);
    if(!"import".equals(job.kind()) || job.parentJobId()!=null) throw new IllegalArgumentException("Expected a root JSONL import job");
    String mode=job.parameters().path("mode").asText();
    if(!java.util.Set.of("merge","replace").contains(mode) || !"jsonl".equals(job.parameters().path("format").asText()))
      throw new IllegalArgumentException("Invalid JSONL import options");
    long offset=job.checkpoint().path("offset").asLong(0),line=job.checkpoint().path("line").asLong(0);
    if(job.checkpoint().path("exhausted").asBoolean(false)) return 0;
    int processed=0,limit=Math.min(properties.getDocumentBatchSize(),100);
    try(var reader=new JsonlRecordReader(input,offset,line,properties.getMaxImportRecordBytes(),mapper)) {
      while(processed<limit) {
        var record=reader.next();
        if(record==null) {jobs.finishImportEnumeration(lease,offset,reader.offset(),reader.completedLines());break;}
        JsonNode locator=mapper.createObjectNode().put("line",record.line()).put("offset",record.offset());
        String error=record.errorCode();
        if(error==null && (!record.value().path("schema_version").isTextual() || !"1".equals(record.value().path("schema_version").textValue())))
          error="invalid_schema_version";
        if(error!=null) jobs.commitImportRecord(lease,offset,record.nextOffset(),record.line(),locator,error,null);
        else {
          try {
            jobs.commitImportRecord(lease,offset,record.nextOffset(),record.line(),locator,null,tx->{
              var result=writer.apply(job.collectionId(),job.id(),record.value(),mode);
              return new JobRepository.ImportResult(result.documentId(),result.vectorsInserted());
            });
          } catch(V2Exception invalid) {
            if(invalid.status()>=500 || invalid.status()==429) throw invalid;
            jobs.commitImportRecord(lease,offset,record.nextOffset(),record.line(),locator,invalid.code(),null);
          }
        }
        offset=record.nextOffset();processed++;
      }
    }
    return processed;
  }
}
