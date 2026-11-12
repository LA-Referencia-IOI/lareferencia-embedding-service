package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.job.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Import worker remains disabled unless explicitly enabled during rollout. */
@Component
@ConditionalOnProperty(name={"app.dvp.enabled","app.dvp.import-enabled"},havingValue="true")
public class ImportHandler implements JobHandler {
  private final ImportFileRepository files;private final JsonlImportProcessor jsonl;private final JsonImportProcessor json;
  private final ImportRecordWriter writer;private final ObjectMapper mapper;private final DvpProperties properties;
  public ImportHandler(ImportFileRepository files,JsonlImportProcessor jsonl,JsonImportProcessor json,ImportRecordWriter writer,
      ObjectMapper mapper,DvpProperties properties) {
    this.files=files;this.jsonl=jsonl;this.json=json;this.writer=writer;this.mapper=mapper;this.properties=properties;
  }
  public String kind(){return "import";}
  public JsonNode publicParameters(JsonNode internal) {return mapper.createObjectNode().put("format",internal.path("format").asText()).put("mode",internal.path("mode").asText());}
  public void run(JobRepository.Lease lease,JobRepository jobs) throws IOException {
    var job=jobs.find(lease.jobId()).orElseThrow(JobRepository.LeaseLostException::new);
    if(job.parentJobId()==null) {
      try {
        if("jsonl".equals(job.parameters().path("format").asText())) jsonl.process(lease,files.openForActiveJob(job.id()));
        else if("json".equals(job.parameters().path("format").asText())) json.process(lease,()->files.openForActiveJob(job.id()));
        else throw new IllegalStateException("Invalid stored import format");
      } catch(V2Exception e) {
        if(e.code().equals("import_file_unavailable") || e.code().equals("import_file_corrupt")){jobs.fail(lease,e.code());return;}
        if(e.status()>=500 || e.status()==429)return;
        throw e;
      } catch(org.springframework.dao.TransientDataAccessException e) {return;}
      return;
    }
    jobs.materialize(lease);
    for(int n=0;n<Math.min(100,properties.getDocumentBatchSize());n++) {
      var next=jobs.claimItem(lease);if(next.isEmpty())break;var item=next.get();
      try {
        JsonNode record=readRetry(job,item);
        var counters=new HashMap<String,Long>();counters.put("documents_read",1L);counters.put("documents_processed",1L);
        jobs.complete(lease,item,"succeeded",null,counters,null,tx->{
          var result=writer.apply(job.collectionId(),job.id(),record,job.parameters().path("mode").asText());
          counters.put("vectors_imported",(long)result.vectorsInserted());
        });
      } catch(V2Exception e) {
        if(e.status()>=500 || e.status()==429)jobs.retryOrFail(lease,item,e.code(),1000);
        else jobs.complete(lease,item,"failed",e.code(),Map.of("documents_read",1L,"documents_failed",1L),null,tx->{});
      } catch(IOException e) {jobs.retryOrFail(lease,item,"import_file_unavailable",1000);}
    }
  }
  private JsonNode readRetry(JobRepository.Job job,JobRepository.ItemLease item) throws IOException {
    if("jsonl".equals(job.parameters().path("format").asText())) {
      long line=item.locator().path("line").asLong(-1),offset=item.locator().path("offset").asLong(-1);
      if(line<1 || offset<0)throw new V2Exception(422,"invalid_locator","Import locator is invalid");
      try(var reader=new JsonlRecordReader(files.openForActiveJob(job.id()),offset,line-1,properties.getMaxImportRecordBytes(),mapper)) {
        var record=reader.next();
        if(record==null || record.line()!=line || record.offset()!=offset)throw new V2Exception(422,"invalid_locator","Import locator is invalid");
        if(record.errorCode()!=null)throw new V2Exception(422,record.errorCode(),"Import record is invalid");
        if(!record.value().path("schema_version").isTextual() || !"1".equals(record.value().path("schema_version").textValue()))
          throw new V2Exception(422,"invalid_schema_version","Import schema version is invalid");
        return record.value();
      }
    }
    if("json".equals(job.parameters().path("format").asText())) {
      long ordinal=item.locator().path("ordinal").asLong(-1);if(ordinal<1)throw new V2Exception(422,"invalid_locator","Import locator is invalid");
      try(var probe=new JsonDocumentReader(files.openForActiveJob(job.id()),properties.getMaxImportRecordBytes(),mapper)) {
        if(!probe.schemaKnownBeforeDocuments()) while(probe.next()!=null) { }
      }
      try(var reader=new JsonDocumentReader(files.openForActiveJob(job.id()),properties.getMaxImportRecordBytes(),mapper)) {
        JsonDocumentReader.Record record=null;for(long n=0;n<ordinal;n++){record=reader.next();if(record==null)throw new V2Exception(422,"invalid_locator","Import locator is invalid");}
        if(record.errorCode()!=null)throw new V2Exception(422,record.errorCode(),"Import record is invalid");
        if(record.value().has("schema_version"))throw new V2Exception(422,"invalid_record","Import record is invalid");return record.value();
      }
    }
    throw new IllegalStateException("Invalid stored import format");
  }
}
