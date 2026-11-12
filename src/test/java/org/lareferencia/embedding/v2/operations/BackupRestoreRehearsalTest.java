package org.lareferencia.embedding.v2.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.lareferencia.embedding.integration.TestDatabase;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Opt-in rehearsal only: dump one fresh fixture database into another fresh fixture database. */
@EnabledIfEnvironmentVariable(named="DVP_RECOVERY_REHEARSAL",matches="1")
@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.workers-enabled=false","app.dvp.import-enabled=false"})
class BackupRestoreRehearsalTest {
  private static String sourceUrl;
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){requireLocalTestServer();sourceUrl=TestDatabase.create();r.add("spring.datasource.url",()->sourceUrl);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");}
  @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
  @TempDir Path temporary;
  @Test void restoreRegistryVectorTableJobAndDurableFile() throws Exception {
    URI root=URI.create(System.getenv("DVP_TEST_DB_URL").substring(5));
    if(!Set.of("127.0.0.1","localhost","::1").contains(root.getHost()) || !"/dvp_test".equals(root.getPath()))throw new IllegalStateException("Rehearsal requires local disposable dvp_test server");
    UUID c=UUID.randomUUID(),p=UUID.randomUUID(),i=UUID.randomUUID(),job=UUID.randomUUID();String table="vec_"+i.toString().replace("-","");String key=UUID.randomUUID()+".data";
    byte[] bytes="{\"schema_version\":\"1\",\"external_id\":\"synthetic\"}\n".getBytes(StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",c,"recovery-"+c,"synthetic");
    jdbc.update("INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint) VALUES (?,?,'synthetic','v1',2,'import_only','{}',?)",p,"recovery-"+p,p.toString().replace("-","").repeat(2));
    jdbc.update("INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state) VALUES (?,?,?,?,'{}','{\"mode\":\"none\"}','cosine','none',?,'ready','disabled')",i,c,"recovery",p,table);
    jdbc.execute("CREATE TABLE dvp."+table+" (id uuid PRIMARY KEY,embedding vector(2) NOT NULL)");jdbc.update("INSERT INTO dvp."+table+" VALUES (?, '[1,2]')",UUID.randomUUID());
    jdbc.update("INSERT INTO dvp.jobs(id,collection_id,kind,status,parameters,checkpoint) VALUES (?,?,'import','succeeded','{}','{\"ordinal\":1}')",job,c);
    jdbc.update("INSERT INTO dvp.import_files(job_id,storage_key,sha256,format,byte_size) VALUES (?,?,?,'jsonl',?)",job,key,hash,bytes.length);
    Path files=Files.createDirectory(temporary.resolve("files")),backup=Files.createDirectory(temporary.resolve("backup")),restoredFiles=Files.createDirectory(temporary.resolve("restored"));Files.write(files.resolve(key),bytes);
    Files.copy(files.resolve(key),backup.resolve(key));Path dump=backup.resolve("database.dump");
    String sourceName=URI.create(sourceUrl.substring(5)).getPath().substring(1);
    runDump(dump,sourceName);
    String restored=TestDatabase.createWithoutExtension();String restoredName=URI.create(restored.substring(5)).getPath().substring(1);runRestore(dump,restoredName);
    Files.copy(backup.resolve(key),restoredFiles.resolve(key));
    try(var connection=DriverManager.getConnection(restored,"postgres","");var statement=connection.createStatement()) {
      try(var rs=statement.executeQuery("SELECT j.checkpoint->>'ordinal',f.storage_key,f.sha256 FROM dvp.jobs j JOIN dvp.import_files f ON f.job_id=j.id WHERE j.id='"+job+"'")){assertTrue(rs.next());assertEquals("1",rs.getString(1));assertEquals(key,rs.getString(2));assertEquals(hash,rs.getString(3).trim());}
      try(var rs=statement.executeQuery("SELECT embedding::text FROM dvp."+table)){assertTrue(rs.next());assertEquals("[1,2]",rs.getString(1));}
      try(var rs=statement.executeQuery("SELECT count(*) FROM dvp.indexations WHERE id='"+i+"'")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
    }
    assertEquals(hash,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(restoredFiles.resolve(key)))));
  }
  private void runDump(Path dump,String database) throws Exception {
    Process p=new ProcessBuilder("docker","compose","-f","docker-compose.test.yml","exec","-T","pgvector-test","pg_dump","--format=custom","--no-owner","--username=postgres",database).redirectOutput(dump.toFile()).redirectErrorStream(false).start();
    String error=new String(p.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);if(p.waitFor()!=0)throw new IllegalStateException("Disposable dump failed: "+error);
  }
  private void runRestore(Path dump,String database) throws Exception {
    Process p=new ProcessBuilder("docker","compose","-f","docker-compose.test.yml","exec","-T","pgvector-test","pg_restore","--exit-on-error","--no-owner","--username=postgres","--dbname",database).redirectInput(dump.toFile()).redirectErrorStream(true).start();
    String error=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);if(p.waitFor()!=0)throw new IllegalStateException("Disposable restore failed: "+error);
  }
  private static void requireLocalTestServer() {
    String root=System.getenv("DVP_TEST_DB_URL");if(root==null || !root.startsWith("jdbc:postgresql://"))throw new IllegalStateException("A local disposable test database is required");
    var uri=java.net.URI.create(root.substring(5));int port=Integer.parseInt(System.getenv().getOrDefault("DVP_TEST_DB_PORT","55439"));
    if(!java.util.Set.of("127.0.0.1","localhost","::1").contains(uri.getHost()) || uri.getPort()!=port || !"/dvp_test".equals(uri.getPath()) || uri.getRawQuery()!=null || uri.getUserInfo()!=null)
      throw new IllegalStateException("A local disposable test database is required");
  }

}
