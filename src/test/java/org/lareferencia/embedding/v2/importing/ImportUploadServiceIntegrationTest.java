package org.lareferencia.embedding.v2.importing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockPart;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.util.LinkedMultiValueMap;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.workers-enabled=false","app.dvp.import-enabled=true","app.dvp.read-connections=1","app.dvp.inference-concurrency=1"})
@AutoConfigureMockMvc
class ImportUploadServiceIntegrationTest {
  private static Path root;
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
    try {root=Files.createTempDirectory("dvp-import-upload-test-");}catch(IOException e){throw new IllegalStateException(e);}
    r.add("app.dvp.file-root",()->root.toString());
  }
  @Autowired ImportUploadService upload;@Autowired DurableImportStore store;@Autowired ObjectMapper mapper;
  @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
  @Autowired org.lareferencia.embedding.v2.job.JobRepository jobs;@Autowired org.lareferencia.embedding.v2.job.JobHandlers handlers;
  @Autowired org.lareferencia.embedding.v2.job.JobService jobService;
  @Autowired ImportCleanup cleanup;
  @Autowired MockMvc mvc;
  @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @Autowired @Qualifier("readDataSource") javax.sql.DataSource readDataSource;
  @Autowired java.util.concurrent.ThreadPoolExecutor inferenceExecutor;
  @org.junit.jupiter.api.AfterEach void releaseTestJobs() {
    jdbc.update("UPDATE dvp.jobs SET status='failed',lease_owner=NULL,lease_until=NULL WHERE status IN ('queued','running')");
  }
  private UUID collection(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",id,"c-"+id,"test");return id;}
  @Test void replayReturnsOriginalJobAndDiscardsOnlyTheRedundantFile() throws Exception {
    UUID collection=collection();String key=UUID.randomUUID().toString();byte[] contents="{\"schema_version\":\"1\"}\n".getBytes(StandardCharsets.UTF_8);
    long before;try(var paths=Files.list(root)){before=paths.filter(p->p.toString().endsWith(".data")).count();}
    var options=mapper.readTree("{\"format\":\"jsonl\",\"mode\":\"merge\"}");
    var prior=metrics.find("dvp.import.registered.bytes").summary();double previousBytes=prior==null?0:prior.totalAmount();
    var first=upload.upload(collection,new ByteArrayInputStream(contents),options,key,new LinkedMultiValueMap<>());
    var replay=upload.upload(collection,new ByteArrayInputStream(contents),options,key,new LinkedMultiValueMap<>());
    assertEquals(202,first.status());assertEquals(first.body(),replay.body());assertEquals(first.headers(),replay.headers());
    assertEquals(previousBytes+contents.length,metrics.find("dvp.import.registered.bytes").summary().totalAmount());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.jobs WHERE id=?",Integer.class,first.jobId()));
    String fileKey=jdbc.queryForObject("SELECT storage_key FROM dvp.import_files WHERE job_id=?",String.class,first.jobId());
    assertArrayEquals(contents,store.open(fileKey).readAllBytes());
    try(var paths=Files.list(root)){assertEquals(before+1,paths.filter(p->p.toString().endsWith(".data")).count());}
    var conflict=assertThrows(V2Exception.class,()->upload.upload(collection,new ByteArrayInputStream("different".getBytes(StandardCharsets.UTF_8)),options,key,new LinkedMultiValueMap<>()));
    assertEquals("idempotency_conflict",conflict.code());assertArrayEquals(contents,store.open(fileKey).readAllBytes());
  }
  @Test void concurrentMatchingUploadsRegisterOneJobAndOneDurableFile() throws Exception {
    UUID c=collection();String key=UUID.randomUUID().toString();var options=mapper.readTree("{\"format\":\"jsonl\",\"mode\":\"merge\"}");
    var gate=new CyclicBarrier(2);
    long before;try(var paths=Files.list(root)){before=paths.filter(p->p.toString().endsWith(".data")).count();}
    try(var executor=Executors.newFixedThreadPool(2)) {
      var calls=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()->{gate.await(5,TimeUnit.SECONDS);
        return upload.upload(c,new ByteArrayInputStream("{}\n".getBytes(StandardCharsets.UTF_8)),options,key,new LinkedMultiValueMap<>());})).toList();
      var first=calls.get(0).get(10,TimeUnit.SECONDS);var second=calls.get(1).get(10,TimeUnit.SECONDS);assertEquals(first.jobId(),second.jobId());
    }
    try(var paths=Files.list(root)){assertEquals(before+1,paths.filter(p->p.toString().endsWith(".data")).count());}
  }
  @Test void retryReusesTheOriginalFileAndReplaysOnlyFailedRecords() throws Exception {
    UUID c=collection(),doc=UUID.randomUUID();jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,'doc')",doc,c);
    String file="{\"schema_version\":\"1\",\"external_id\":\"doc\",\"metadata\":{\"year\":2025},\"expected_revision\":2}\n";
    var options=mapper.readTree("{\"format\":\"jsonl\",\"mode\":\"merge\"}");
    var submitted=upload.upload(c,new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)),options,UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    var lease=jobs.claim("root-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(submitted.jobId(),lease.jobId());
    handlers.find("import").orElseThrow().run(lease,jobs);assertTrue(jobs.finishIfComplete(lease));
    assertEquals("completed_with_errors",jobs.find(submitted.jobId()).orElseThrow().status());
    assertEquals("revision_conflict",jobs.errors(submitted.jobId(),null,10).getFirst().code());
    assertNotNull(jdbc.queryForObject("SELECT expires_at FROM dvp.import_files WHERE job_id=?",java.sql.Timestamp.class,submitted.jobId()));
    jdbc.update("UPDATE dvp.documents SET revision=2 WHERE id=?",doc);
    var retry=jobService.retry(submitted.jobId().toString(),UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    var retryLease=jobs.claim("retry-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(retry.jobId(),retryLease.jobId());
    handlers.find("import").orElseThrow().run(retryLease,jobs);assertTrue(jobs.finishIfComplete(retryLease));
    assertEquals("succeeded",jobs.find(retry.jobId()).orElseThrow().status());
    assertEquals(3L,jdbc.queryForObject("SELECT revision FROM dvp.documents WHERE id=?",Long.class,doc));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.import_files WHERE job_id=?",Integer.class,submitted.jobId()));
  }
  @Test void multipartEndpointRegistersAndProcessesAJsonlFile() throws Exception {
    UUID c=collection();var file=new MockPart("file","records.jsonl","{\"schema_version\":\"1\",\"external_id\":\"doc\",\"metadata\":{\"year\":2025}}\n".getBytes(StandardCharsets.UTF_8));
    var options=new MockPart("options","options.json","{\"format\":\"jsonl\",\"mode\":\"merge\"}".getBytes(StandardCharsets.UTF_8));
    options.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v2/collections/"+c+"/imports")
        .part(file,options).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted()).andReturn();
    UUID job=UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
    var lease=jobs.claim("http-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(job,lease.jobId());
    handlers.find("import").orElseThrow().run(lease,jobs);assertTrue(jobs.finishIfComplete(lease));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?",Integer.class,c));
  }
  @Test void cleanupProtectsActiveRetriesAndLockedUploadsThenRemovesExpiredFiles() throws Exception {
    var old=java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(40)));
    DurableImportStore.StoredFile orphan=store.store(new ByteArrayInputStream("orphan".getBytes(StandardCharsets.UTF_8)));
    Path orphanPath=root.resolve(orphan.key());Files.setLastModifiedTime(orphanPath,old);
    cleanup.runBatch();assertTrue(Files.exists(orphanPath));orphan.close();cleanup.runBatch();assertFalse(Files.exists(orphanPath));
    Path partial=root.resolve(UUID.randomUUID()+".part");Files.writeString(partial,"unfinished");Files.setLastModifiedTime(partial,old);
    try(var channel=java.nio.channels.FileChannel.open(partial,StandardOpenOption.WRITE);var held=channel.lock()) {
      cleanup.runBatch();assertTrue(Files.exists(partial));
    }
    cleanup.runBatch();assertFalse(Files.exists(partial));
    UUID c=collection();var options=mapper.readTree("{\"format\":\"jsonl\",\"mode\":\"merge\"}");
    var submitted=upload.upload(c,new ByteArrayInputStream("{}\n".getBytes(StandardCharsets.UTF_8)),options,UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    String key=jdbc.queryForObject("SELECT storage_key FROM dvp.import_files WHERE job_id=?",String.class,submitted.jobId());Path path=root.resolve(key);Files.setLastModifiedTime(path,old);
    jdbc.update("UPDATE dvp.jobs SET status='succeeded',finished_at=clock_timestamp() WHERE id=?",submitted.jobId());
    jdbc.update("UPDATE dvp.import_files SET expires_at=clock_timestamp()-interval '1 second' WHERE job_id=?",submitted.jobId());
    UUID child=UUID.randomUUID();jdbc.update("INSERT INTO dvp.jobs(id,collection_id,parent_job_id,kind,status,parameters) VALUES (?,?,?,'import','queued','{}'::jsonb)",child,c,submitted.jobId());
    cleanup.runBatch();assertTrue(Files.exists(path));
    jdbc.update("UPDATE dvp.jobs SET status='failed',finished_at=clock_timestamp() WHERE id=?",child);
    cleanup.runBatch();assertFalse(Files.exists(path));
    assertEquals("import_file_unavailable",assertThrows(V2Exception.class,()->jobService.retry(submitted.jobId().toString(),UUID.randomUUID().toString(),new LinkedMultiValueMap<>())).code());
  }
  @Test void jsonRetryUsesFailedOrdinalWithoutRepeatingSuccessfulRecords() throws Exception {
    UUID c=collection(),doc=UUID.randomUUID();jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,'doc')",doc,c);
    String file="{\"documents\":[{\"external_id\":\"doc\",\"metadata\":{\"year\":2025},\"expected_revision\":2},"
        +"{\"external_id\":\"other\",\"metadata\":{\"year\":2024}}],\"schema_version\":\"1\"}";
    var options=mapper.readTree("{\"format\":\"json\",\"mode\":\"merge\"}");
    var rootJob=upload.upload(c,new ByteArrayInputStream(file.getBytes(StandardCharsets.UTF_8)),options,UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    var lease=jobs.claim("root-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(rootJob.jobId(),lease.jobId());
    handlers.find("import").orElseThrow().run(lease,jobs);assertTrue(jobs.finishIfComplete(lease));
    assertEquals("completed_with_errors",jobs.find(rootJob.jobId()).orElseThrow().status());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=? AND external_id='other'",Integer.class,c));
    jdbc.update("UPDATE dvp.documents SET revision=2 WHERE id=?",doc);
    var retry=jobService.retry(rootJob.jobId().toString(),UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    var retryLease=jobs.claim("retry-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(retry.jobId(),retryLease.jobId());
    handlers.find("import").orElseThrow().run(retryLease,jobs);assertTrue(jobs.finishIfComplete(retryLease));
    assertEquals("succeeded",jobs.find(retry.jobId()).orElseThrow().status());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.job_items WHERE job_id=?",Integer.class,retry.jobId()));
    assertEquals(1L,jdbc.queryForObject("SELECT revision FROM dvp.documents WHERE collection_id=? AND external_id='other'",Long.class,c));
  }
  @Test void openApiShowsTheMultipartImportRoute() throws Exception {
    var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api-docs"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
    var operation=mapper.readTree(response.getResponse().getContentAsString()).path("paths").path("/api/v2/collections/{collection}/imports").path("post");
    assertTrue(operation.path("responses").has("202"));
    assertTrue(operation.path("requestBody").path("content").has("multipart/form-data"));
  }
  @Test void missingAndDuplicateMultipartPartsReturnV2Errors() throws Exception {
    UUID c=collection();var options=new MockPart("options","options.json","{\"format\":\"jsonl\",\"mode\":\"merge\"}".getBytes(StandardCharsets.UTF_8));
    options.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v2/collections/"+c+"/imports")
        .part(options).header("Idempotency-Key",UUID.randomUUID().toString()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("invalid_request"));
    var file1=new MockPart("file","one.jsonl",new byte[0]);var file2=new MockPart("file","two.jsonl",new byte[0]);
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v2/collections/"+c+"/imports")
        .part(file1,file2,options).header("Idempotency-Key",UUID.randomUUID().toString()))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  }
  @Test void emptyJsonlSucceedsAndMalformedGlobalJsonRequiresAFileCorrection() throws Exception {
    UUID c=collection();var jsonl=mapper.readTree("{\"format\":\"jsonl\",\"mode\":\"merge\"}");
    var empty=upload.upload(c,new ByteArrayInputStream("\ufeff\n\n".getBytes(StandardCharsets.UTF_8)),jsonl,UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    var emptyLease=jobs.claim("empty-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(empty.jobId(),emptyLease.jobId());
    handlers.find("import").orElseThrow().run(emptyLease,jobs);assertTrue(jobs.finishIfComplete(emptyLease));
    assertEquals("succeeded",jobs.find(empty.jobId()).orElseThrow().status());assertEquals(0,jobs.find(empty.jobId()).orElseThrow().counters().path("documents_read").asInt());
    var json=mapper.readTree("{\"format\":\"json\",\"mode\":\"merge\"}");String broken="{\"schema_version\":\"1\",\"documents\":[{\"external_id\":\"good\",\"metadata\":{}},{\"broken\":}]}";
    var submitted=upload.upload(c,new ByteArrayInputStream(broken.getBytes(StandardCharsets.UTF_8)),json,UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    var lease=jobs.claim("broken-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(submitted.jobId(),lease.jobId());
    handlers.find("import").orElseThrow().run(lease,jobs);
    assertEquals("file_correction_required",jobs.find(submitted.jobId()).orElseThrow().errorCode());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=? AND external_id='good'",Integer.class,c));
    assertEquals("file_correction_required",assertThrows(V2Exception.class,()->jobService.retry(submitted.jobId().toString(),UUID.randomUUID().toString(),new LinkedMultiValueMap<>())).code());
  }
  @Test void missingDurableFileFailsTheJobWithASanitizedDiagnostic() throws Exception {
    UUID c=collection();var options=mapper.readTree("{\"format\":\"jsonl\",\"mode\":\"merge\"}");
    var submitted=upload.upload(c,new ByteArrayInputStream("{}\n".getBytes(StandardCharsets.UTF_8)),options,UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    String key=jdbc.queryForObject("SELECT storage_key FROM dvp.import_files WHERE job_id=?",String.class,submitted.jobId());
    Files.delete(root.resolve(key));var lease=jobs.claim("missing-"+UUID.randomUUID(),Set.of("import")).orElseThrow();
    handlers.find("import").orElseThrow().run(lease,jobs);
    assertEquals("failed",jobs.find(submitted.jobId()).orElseThrow().status());assertEquals("import_file_unavailable",jobs.find(submitted.jobId()).orElseThrow().errorCode());
  }
  @Test void corruptedDurableFileFailsBeforeAnyRecordIsApplied() throws Exception {
    UUID c=collection();var options=mapper.readTree("{\"format\":\"jsonl\",\"mode\":\"merge\"}");
    var submitted=upload.upload(c,new ByteArrayInputStream("{}\n".getBytes(StandardCharsets.UTF_8)),options,UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    String key=jdbc.queryForObject("SELECT storage_key FROM dvp.import_files WHERE job_id=?",String.class,submitted.jobId());Files.writeString(root.resolve(key),"bad");
    var lease=jobs.claim("corrupt-"+UUID.randomUUID(),Set.of("import")).orElseThrow();handlers.find("import").orElseThrow().run(lease,jobs);
    assertEquals("failed",jobs.find(submitted.jobId()).orElseThrow().status());assertEquals("import_file_corrupt",jobs.find(submitted.jobId()).orElseThrow().errorCode());
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM dvp.documents WHERE collection_id=?",Integer.class,c));
  }
  @Test void importUnderReadAndInferenceContentionKeepsHeartbeatsAndWritesAvailable() throws Exception {
    UUID c=collection(),blocked=UUID.randomUUID();jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,'blocked')",blocked,c);
    var text=new StringBuilder("{\"schema_version\":\"1\",\"external_id\":\"blocked\",\"metadata\":{\"n\":0}}\n");
    for(int n=1;n<20;n++)text.append("{\"schema_version\":\"1\",\"external_id\":\"doc-").append(n).append("\",\"metadata\":{\"n\":").append(n).append("}}\n");
    var options=mapper.readTree("{\"format\":\"jsonl\",\"mode\":\"merge\"}");
    var submitted=upload.upload(c,new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)),options,UUID.randomUUID().toString(),new LinkedMultiValueMap<>());
    var lease=jobs.claim("load-"+UUID.randomUUID(),Set.of("import")).orElseThrow();assertEquals(submitted.jobId(),lease.jobId());
    var inferenceStarted=new CountDownLatch(1);var inferenceRelease=new CountDownLatch(1);
    inferenceExecutor.execute(()->{inferenceStarted.countDown();try{inferenceRelease.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
    try(var read=readDataSource.getConnection();var lock=jdbc.getDataSource().getConnection();var executor=Executors.newSingleThreadExecutor()) {
      assertTrue(inferenceStarted.await(5,TimeUnit.SECONDS));lock.setAutoCommit(false);
      try(var stmt=lock.prepareStatement("SELECT id FROM dvp.documents WHERE id=? FOR UPDATE")){stmt.setObject(1,blocked);stmt.execute();}
      var pending=executor.submit(()->{handlers.find("import").orElseThrow().run(lease,jobs);return true;});
      try {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%dvp.documents%FOR UPDATE%')",Boolean.class));
        long start=System.nanoTime();assertTrue(jobs.heartbeat(lease));
        jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,'independent')",UUID.randomUUID(),"independent-"+UUID.randomUUID());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<3);
      } finally {lock.rollback();inferenceRelease.countDown();}
      assertTrue(pending.get(10,TimeUnit.SECONDS));assertTrue(jobs.finishIfComplete(lease));
    } finally {inferenceRelease.countDown();}
    assertEquals(20,jobs.find(submitted.jobId()).orElseThrow().counters().path("documents_processed").asInt());
  }
}
