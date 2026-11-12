package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.job.JobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Re-parses a bounded JSON container from the start and resumes by committed record ordinal. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class JsonImportProcessor {
  @FunctionalInterface public interface InputSupplier {InputStream open() throws IOException;}
  private final JobRepository jobs;private final ImportRecordWriter writer;private final ObjectMapper mapper;private final DvpProperties properties;
  public JsonImportProcessor(JobRepository jobs,ImportRecordWriter writer,ObjectMapper mapper,DvpProperties properties) {
    this.jobs=jobs;this.writer=writer;this.mapper=mapper;this.properties=properties;
  }
  public int process(JobRepository.Lease lease,InputSupplier source) throws IOException {
    var job=jobs.find(lease.jobId()).orElseThrow(JobRepository.LeaseLostException::new);
    if(!"import".equals(job.kind()) || job.parentJobId()!=null) throw new IllegalArgumentException("Expected a root JSON import job");
    String mode=job.parameters().path("mode").asText();
    if(!java.util.Set.of("merge","replace").contains(mode) || !"json".equals(job.parameters().path("format").asText()))
      throw new IllegalArgumentException("Invalid JSON import options");
    long ordinal=job.checkpoint().path("ordinal").asLong(0);if(job.checkpoint().path("exhausted").asBoolean(false))return 0;
    int count=0;
    try {
      // If the version follows the documents array, establish it before applying data.
      try(var probe=new JsonDocumentReader(source.open(),properties.getMaxImportRecordBytes(),mapper)) {
        if(!probe.schemaKnownBeforeDocuments()) while(probe.next()!=null) { /* bounded preflight */ }
      }
      try(var reader=new JsonDocumentReader(source.open(),properties.getMaxImportRecordBytes(),mapper)) {
        for(long skipped=0;skipped<ordinal;skipped++) if(reader.next()==null) throw new JsonDocumentReader.SyntaxException();
        while(count<Math.min(properties.getDocumentBatchSize(),100)) {
          var record=reader.next();
          if(record==null){jobs.finishJsonImportEnumeration(lease,ordinal);break;}
          JsonNode locator=mapper.createObjectNode().put("ordinal",record.ordinal());String error=record.errorCode();
          if(error==null && record.value().has("schema_version"))error="invalid_record";
          if(error!=null) jobs.commitJsonImportRecord(lease,ordinal,record.ordinal(),locator,error,null);
          else {
            try {jobs.commitJsonImportRecord(lease,ordinal,record.ordinal(),locator,null,tx->{
              var result=writer.apply(job.collectionId(),job.id(),record.value(),mode);
              return new JobRepository.ImportResult(result.documentId(),result.vectorsInserted());
            });} catch(V2Exception invalid) {
              if(invalid.status()>=500 || invalid.status()==429)throw invalid;
              jobs.commitJsonImportRecord(lease,ordinal,record.ordinal(),locator,invalid.code(),null);
            }
          }
          ordinal=record.ordinal();count++;
        }
      }
    } catch(JsonDocumentReader.SyntaxException e) {jobs.fail(lease,"file_correction_required");}
    return count;
  }
}
