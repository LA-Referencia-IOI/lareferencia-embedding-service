package org.lareferencia.embedding.v2.job;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true",
    "app.dvp.role=all","app.dvp.workers-enabled=true","app.dvp.lease-seconds=3","app.dvp.heartbeat-seconds=1",
    "app.dvp.worker-poll-millis=100","spring.main.allow-bean-definition-overriding=true"})
@Import(WorkerLifecycleIntegrationTest.TestHandler.class)
class WorkerLifecycleIntegrationTest {
  private static final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),completed=new CountDownLatch(1);
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url=TestDatabase.create();r.add("spring.datasource.url",()->url);
    r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
  }
  @TestConfiguration static class TestHandler {
    @Bean JobHandler preparationHandler(ObjectMapper mapper) {
      return new JobHandler() {
        public String kind() { return "prepare"; }
        public JsonNode publicParameters(JsonNode parameters) { return mapper.createObjectNode(); }
        public void run(JobRepository.Lease lease,JobRepository jobs) throws Exception {
          jobs.initializeEnumeration(lease);jobs.materialize(lease);
          var item=jobs.claimItem(lease).orElseThrow();started.countDown();
          if(!release.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("Test timeout");
          jobs.complete(lease,item,"succeeded",null,Map.of("documents_processed",1L),null,jdbc->{});
          completed.countDown();
        }
      };
    }
  }
  @Autowired JobService service;@Autowired JobRepository jobs;@Autowired JobWorker worker;
  @Autowired ObjectMapper mapper;@Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Test void workerExecutesHandlerAndRenewsLeaseWhileBlocked() throws Exception {
    assertTrue(worker.isRunning());UUID c=UUID.randomUUID(),d=UUID.randomUUID();
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,'test')",c,"test-"+c);
    jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,'item')",d,c);
    UUID id=service.create(c,null,"prepare",mapper.createObjectNode(),UUID.randomUUID().toString(),
        "/api/v2/collections/"+c+"/test-jobs",new LinkedMultiValueMap<>()).jobId();
    try {
      assertTrue(started.await(5,TimeUnit.SECONDS));
      Thread.sleep(4000); // Longer than the lease; the independent heartbeat must renew it.
      assertEquals("running",jobs.find(id).orElseThrow().status());
      assertEquals(1,jobs.find(id).orElseThrow().attempts());
      release.countDown();assertTrue(completed.await(5,TimeUnit.SECONDS));
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
      while(System.nanoTime()<deadline && !"succeeded".equals(jobs.find(id).orElseThrow().status())) Thread.sleep(50);
      assertEquals("succeeded",jobs.find(id).orElseThrow().status());
      assertEquals(1,jobs.find(id).orElseThrow().counters().path("documents_processed").asInt());
    } finally { release.countDown(); }
  }
}
