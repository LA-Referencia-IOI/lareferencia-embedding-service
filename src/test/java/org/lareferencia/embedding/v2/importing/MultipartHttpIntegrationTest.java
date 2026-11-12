package org.lareferencia.embedding.v2.importing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.config.location=classpath:/application.properties",
    "app.dvp.enabled=true","app.dvp.workers-enabled=false","app.dvp.import-enabled=true","app.dvp.max-file-bytes=64","app.dvp.max-request-bytes=2048"})
class MultipartHttpIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");
    try {Path root=Files.createTempDirectory("dvp-multipart-test-");r.add("app.dvp.file-root",()->root.toString());}catch(java.io.IOException e){throw new IllegalStateException(e);}
  }
  @LocalServerPort int port;@Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;@Autowired ObjectMapper mapper;
  private UUID collection(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",id,"c-"+id,"test");return id;}
  private HttpResponse<String> request(UUID c,int bytes) throws Exception {
    String boundary="dvp-test-boundary";
    String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"options\"; filename=\"options.json\"\r\nContent-Type: application/json\r\n\r\n{\"format\":\"jsonl\",\"mode\":\"merge\"}\r\n"
        +"--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"records.jsonl\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        +"x".repeat(bytes)+"\r\n--"+boundary+"--\r\n";
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v2/collections/"+c+"/imports"))
        .header("Content-Type","multipart/form-data; boundary="+boundary).header("Idempotency-Key",UUID.randomUUID().toString())
        .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
    return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
  }
  @Test void servletMultipartEnforcesConfiguredFileLimit() throws Exception {
    UUID c=collection();assertEquals(202,request(c,64).statusCode());var oversized=request(c,65);
    assertEquals(413,oversized.statusCode());assertEquals("file_too_large",mapper.readTree(oversized.body()).path("code").asText());
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM dvp.import_files",Integer.class));
  }
}
