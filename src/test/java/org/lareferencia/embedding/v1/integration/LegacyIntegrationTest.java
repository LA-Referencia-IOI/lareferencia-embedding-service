package org.lareferencia.embedding.v1.integration;

import org.lareferencia.embedding.integration.TestDatabase;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lareferencia.embedding.v1.dto.CreateStoreRequest;
import org.lareferencia.embedding.v1.repository.EmbeddingRepository;
import org.lareferencia.embedding.v1.service.*;
import org.lareferencia.embedding.v1.config.PgVectorStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="DVP_TEST_DB_URL", matches=".+")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties="spring.config.location=classpath:/application.properties")
class LegacyIntegrationTest {
  @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
    String url = TestDatabase.create();
    r.add("spring.datasource.url", () -> url);
    r.add("spring.datasource.username", () -> "postgres");
    r.add("spring.datasource.password", () -> "");
  }
  @Autowired StoreService stores;
  @Autowired EmbeddingRepository vectors;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired org.lareferencia.embedding.v1.repository.StoreRepository repository;
  @org.springframework.test.context.bean.override.mockito.MockitoBean EmbeddingProviderService provider;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
  @org.junit.jupiter.api.BeforeEach void providerIdentity() {
    org.mockito.Mockito.when(provider.provider()).thenReturn("ollama");
  }


  @Test void rollbackPublishesNeitherStoreNorTableAndAnotherServiceSeesCommittedStores() {
    var tx = new TransactionTemplate(transactionManager);
    String model = UUID.randomUUID().toString();
    UUID[] id = new UUID[1];
    tx.executeWithoutResult(status -> {
      id[0] = stores.createStore(new CreateStoreRequest(model, model, 3, false)).id();
      status.setRollbackOnly();
    });
    assertTrue(repository.findByModelAndDimensions(model, 3).isEmpty());
    String table = new StoreTableNamingService().tableName(id[0]);
    assertNull(jdbc.queryForObject("SELECT to_regclass(?)", String.class, "public." + table));
    vectors.ensureStoreTableExists(id[0], 3, false);
    assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)", String.class, "public." + table));
    var other = new StoreService(repository, provider, vectors);
    stores.createStore(new CreateStoreRequest(model, model, 3, false));
    assertEquals(model, other.getRequiredStore(model, 3).getModel());
  }

  @Test void competingCaseVariantsPersistOnlyOneStore() throws Exception {
    String suffix = UUID.randomUUID().toString();
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = List.of("model-" + suffix, "MODEL-" + suffix).stream().map(name -> executor.submit(() -> {
        barrier.await(5, TimeUnit.SECONDS);
        try {
          stores.createStore(new CreateStoreRequest(name, name, 3, false));
          return true;
        } catch (org.springframework.dao.DataIntegrityViolationException | org.lareferencia.embedding.v1.exception.StoreAlreadyExistsException e) {
          return false;
        }
      })).toList();
      int successes = 0;
      for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) successes++;
      assertEquals(1, successes);
      assertEquals(1, repository.findByModelAndDimensions("model-" + suffix, 3).size());
    }
  }

  @Test void vectorsKeepTheirValuesAndDynamicTableMarkersAreNotPublishedOnRollback() {
    UUID id = UUID.randomUUID();
    var tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status -> {
      vectors.saveEmbeddings(id, 3, Map.of("abc", new float[] {1,2,3}));
      status.setRollbackOnly();
    });
    assertTrue(vectors.findByHashes(id, 3, List.of("abc")).isEmpty());
    vectors.saveEmbeddings(id, 3, Map.of("abc", new float[] {1,2,3}));
    assertArrayEquals(new float[] {1,2,3}, vectors.findByHashes(id, 3, List.of("abc")).get("abc"));
  }

  @Test void httpBatchOrderCacheAndProviderMismatchPreserveLegacyContract() throws Exception {
    String name="http-"+UUID.randomUUID();
    var create=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/stores")
        .contentType("application/json").content(mapper.writeValueAsString(Map.of("name",name,"model",name,"dimensions",3))))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
    UUID id=UUID.fromString(mapper.readTree(create.getResponse().getContentAsString()).path("id").asText());
    org.mockito.Mockito.when(provider.embed(List.of("first","second"),name,3))
        .thenReturn(new EmbeddingProviderService.EmbeddingBatchResult(
            List.of(new float[] {1,0,0},new float[] {0,1,0}),"ollama",new EmbeddingProviderService.UsageData(2,2)));
    String payload=mapper.writeValueAsString(Map.of("model",name,"dimensions",3,"input",List.of("First","First","Second")));
    for(int i=0;i<2;i++) {
      var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/embeddings")
          .contentType("application/json").content(payload))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
      var json=mapper.readTree(result.getResponse().getContentAsString());
      assertEquals("list",json.path("object").asText());assertEquals(name,json.path("storeName").asText());
      assertEquals(3,json.path("data").size());assertEquals(1,json.path("data").get(0).path("embedding").get(0).asInt());
      assertEquals(json.path("data").get(0).path("embedding"),json.path("data").get(1).path("embedding"));
      assertEquals(1,json.path("data").get(2).path("embedding").get(1).asInt());
      assertEquals(i==0?2:0,json.path("usage").path("total_tokens").asInt());
    }
    org.mockito.Mockito.verify(provider,org.mockito.Mockito.times(1)).embed(List.of("first","second"),name,3);
    org.mockito.Mockito.when(provider.provider()).thenReturn("openai");
    for(String text:List.of("First","Not cached")) {
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/embeddings")
          .contentType("application/json").content(mapper.writeValueAsString(Map.of("model",name,"dimensions",3,"input",text))))
          .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }
    assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM public."+new StoreTableNamingService().tableName(id),Integer.class));
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/stores")
        .contentType("application/json").content(mapper.writeValueAsString(Map.of("name",name.toUpperCase(java.util.Locale.ROOT),"model",name,"dimensions",3))))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
  }
}
