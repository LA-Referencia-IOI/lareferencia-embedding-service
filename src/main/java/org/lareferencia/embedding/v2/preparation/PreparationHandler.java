package org.lareferencia.embedding.v2.preparation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.job.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class PreparationHandler implements JobHandler {
  private final PreparationService preparation;private final DvpProperties properties;
  public PreparationHandler(PreparationService preparation,DvpProperties properties) {
    this.preparation=preparation;this.properties=properties;
  }
  public String kind() { return "prepare"; }
  public JsonNode publicParameters(JsonNode internal) { return internal.deepCopy(); }
  public void run(JobRepository.Lease lease,JobRepository jobs) {
    var job=jobs.find(lease.jobId()).orElseThrow(JobRepository.LeaseLostException::new);
    jobs.initializeEnumeration(lease);jobs.materialize(lease);
    for(int count=0;count<Math.min(properties.getDocumentBatchSize(),100);count++) {
      var next=jobs.claimItem(lease);if(next.isEmpty()) break;var item=next.get();
      try {
        var increments=new HashMap<String,Long>();increments.put("documents_read",1L);increments.put("documents_processed",1L);
        jobs.complete(lease,item,"succeeded",null,increments,null,jdbc->preparation.prepareDocument(jdbc,job,item,increments));
      } catch(DocumentGone e) {
        jobs.complete(lease,item,"skipped",null,Map.of("documents_read",1L,"items_skipped",1L),null,jdbc->{});
      } catch(V2Exception e) {
        jobs.complete(lease,item,"failed",e.code(),Map.of("documents_read",1L,"documents_failed",1L),null,jdbc->{});
      } catch(org.springframework.dao.TransientDataAccessException e) {
        jobs.retryOrFail(lease,item,"database_contention",1000);
      }
    }
  }
  public static final class DocumentGone extends RuntimeException { }
}
