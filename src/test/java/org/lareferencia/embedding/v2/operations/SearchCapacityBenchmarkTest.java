package org.lareferencia.embedding.v2.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.integration.TestDatabase;
import org.lareferencia.embedding.v2.search.SearchService;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Explicitly opt-in synthetic smoke benchmark; creates only an isolated TestDatabase fixture. */
@EnabledIfEnvironmentVariable(named="DVP_BENCHMARK",matches="1")
@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL",matches=".+")
@SpringBootTest(properties={"spring.config.location=classpath:/application.properties","app.dvp.enabled=true","app.dvp.workers-enabled=false","app.dvp.import-enabled=false"})
class SearchCapacityBenchmarkTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r){requireLocalTestServer();String url=TestDatabase.create();r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->"postgres");r.add("spring.datasource.password",()->"");}
  @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;@Autowired SearchService search;@Autowired ObjectMapper mapper;
  @Test void synthetic1024DimensionalSearch() throws Exception {
    final int count=256,dim=1024,iterations=30;UUID c=UUID.randomUUID(),p=UUID.randomUUID(),i=UUID.randomUUID();String table="vec_"+i.toString().replace("-","");
    jdbc.update("INSERT INTO dvp.collections(id,slug,name) VALUES (?,?,?)",c,"benchmark-"+c,"synthetic");
    jdbc.update("INSERT INTO dvp.embedding_profiles(id,slug,model,model_revision,dimensions,execution_mode,preparation,fingerprint) VALUES (?,?,'synthetic','v1',?,'import_only','{\"version\":\"identity-v1\",\"document_prefix\":\"\",\"query_prefix\":\"\"}',?)",p,"bench-"+p,dim,p.toString().replace("-","").repeat(2));
    jdbc.update("INSERT INTO dvp.indexations(id,collection_id,slug,profile_id,selection,chunking,metric,ann_strategy,vector_table,state,ann_state) VALUES (?,?,?,?,'{}','{\"mode\":\"none\"}','cosine','hnsw_vector',?,'ready','ready')",i,c,"benchmark",p,table);
    jdbc.execute("CREATE TABLE dvp."+table+" (unit_id uuid PRIMARY KEY,document_id uuid NOT NULL,source_text_id uuid NOT NULL,source_revision bigint NOT NULL,indexation_id uuid NOT NULL,embedding vector(1024) NOT NULL,origin varchar(10) NOT NULL)");
    long load=System.nanoTime();for(int n=0;n<count;n++){
      UUID d=UUID.randomUUID(),t=UUID.randomUUID(),u=UUID.randomUUID();String v="[1,"+(n/(double)count)+",0"+",0".repeat(dim-3)+"]";
      jdbc.update("INSERT INTO dvp.documents(id,collection_id,external_id) VALUES (?,?,?)",d,c,"synthetic:"+n);
      jdbc.update("INSERT INTO dvp.source_texts(id,document_id,text_key,kind,content,content_hash) VALUES (?,?,'abstract','abstract','synthetic',?)",t,d,"a".repeat(64));
      jdbc.update("INSERT INTO dvp.vector_units(id,collection_id,indexation_id,document_id,source_text_id,source_revision,ordinal,start_cp,end_cp,input_hash) VALUES (?,?,?,?,?,1,0,0,9,?)",u,c,i,d,t,"b".repeat(64));
      jdbc.update("INSERT INTO dvp."+table+" VALUES (?,?,?,?,?,?::vector,'imported')",u,d,t,1,i,v);
    }load=System.nanoTime()-load;
    long build=System.nanoTime();jdbc.execute("CREATE INDEX "+table+"_ann ON dvp."+table+" USING hnsw (embedding vector_cosine_ops)");build=System.nanoTime()-build;
    var vector=mapper.createArrayNode();vector.add(1);vector.add(0.42);for(int n=2;n<dim;n++)vector.add(0);
    var request=mapper.createObjectNode();request.putObject("query").set("vector",vector);request.put("top_n",10);long[] exact=new long[iterations],approx=new long[iterations];Set<?> expected=Set.of();double recall=0;
    for(int n=-3;n<iterations;n++){
      request.put("mode","exact");long start=System.nanoTime();var ex=search.search(c.toString(),i.toString(),request);long et=System.nanoTime()-start;
      request.put("mode","approximate");start=System.nanoTime();var an=search.search(c.toString(),i.toString(),request);long at=System.nanoTime()-start;
      if(n>=0){exact[n]=et;approx[n]=at;var ids=new HashSet<>();for(Object item:(List<?>)ex.get("results"))ids.add(((Map<?,?>)item).get("document_id"));int found=0;for(Object item:(List<?>)an.get("results"))if(ids.contains(((Map<?,?>)item).get("document_id")))found++;recall+=found/10.0;}
    }
    Arrays.sort(exact);Arrays.sort(approx);Long tableBytes=jdbc.queryForObject("SELECT pg_total_relation_size(?::regclass)",Long.class,"dvp."+table);Long indexBytes=jdbc.queryForObject("SELECT pg_relation_size(?::regclass)",Long.class,"dvp."+table+"_ann");
    String db=jdbc.queryForObject("SELECT version()",String.class),pgvector=jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'",String.class);
    System.out.printf(Locale.ROOT,"DVP_BENCH count=%d dims=%d iterations=%d cpus=%d max_heap_mb=%d db=%s pgvector=%s load_ms=%.1f hnsw_build_ms=%.1f total_bytes=%d hnsw_bytes=%d exact_ms_p50=%.3f exact_ms_p95=%.3f exact_ms_p99=%.3f approximate_ms_p50=%.3f approximate_ms_p95=%.3f approximate_ms_p99=%.3f recall_at_10=%.3f%n",count,dim,iterations,Runtime.getRuntime().availableProcessors(),ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax()/1024/1024,db,pgvector,load/1e6,build/1e6,tableBytes,indexBytes,exact[14]/1e6,exact[28]/1e6,exact[29]/1e6,approx[14]/1e6,approx[28]/1e6,approx[29]/1e6,recall/iterations);
  }
  private static void requireLocalTestServer() {
    String root=System.getenv("DVP_TEST_DB_URL");if(root==null || !root.startsWith("jdbc:postgresql://"))throw new IllegalStateException("A local disposable test database is required");
    var uri=java.net.URI.create(root.substring(5));int port=Integer.parseInt(System.getenv().getOrDefault("DVP_TEST_DB_PORT","55439"));
    if(!java.util.Set.of("127.0.0.1","localhost","::1").contains(uri.getHost()) || uri.getPort()!=port || !"/dvp_test".equals(uri.getPath()) || uri.getRawQuery()!=null || uri.getUserInfo()!=null)
      throw new IllegalStateException("A local disposable test database is required");
  }

}
