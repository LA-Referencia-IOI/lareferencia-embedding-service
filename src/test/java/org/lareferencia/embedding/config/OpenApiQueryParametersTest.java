package org.lareferencia.embedding.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v2.collection.CollectionController;
import org.lareferencia.embedding.v2.collection.CollectionService;
import org.lareferencia.embedding.v2.common.V2ExceptionHandler;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.lareferencia.embedding.v2.document.DocumentController;
import org.lareferencia.embedding.v2.document.DocumentService;
import org.lareferencia.embedding.v2.generation.GenerationController;
import org.lareferencia.embedding.v2.generation.GenerationJobService;
import org.lareferencia.embedding.v2.indexation.IndexationController;
import org.lareferencia.embedding.v2.indexation.IndexationService;
import org.lareferencia.embedding.v2.job.JobController;
import org.lareferencia.embedding.v2.job.JobService;
import org.lareferencia.embedding.v2.profile.ProfileController;
import org.lareferencia.embedding.v2.profile.ProfileService;
import org.lareferencia.embedding.v2.retrieval.SharedVectorRetrievalController;
import org.lareferencia.embedding.v2.retrieval.VectorRetrievalController;
import org.lareferencia.embedding.v2.retrieval.VectorRetrievalService;
import org.lareferencia.embedding.v2.search.SearchController;
import org.lareferencia.embedding.v2.search.SearchService;
import org.lareferencia.embedding.v2.search.SharedSearchController;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@WebMvcTest(
    controllers = {
      CollectionController.class,
      DocumentController.class,
      GenerationController.class,
      IndexationController.class,
      JobController.class,
      ProfileController.class,
      SearchController.class,
      SharedSearchController.class,
      SharedVectorRetrievalController.class,
      VectorRetrievalController.class
    },
    properties = {"app.dvp.enabled=true", "app.dvp.role=API", "springdoc.api-docs.path=/api-docs"})
@ImportAutoConfiguration({
  SpringDocConfiguration.class,
  SpringDocConfigProperties.class,
  SpringDocWebMvcConfiguration.class
})
class OpenApiQueryParametersTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @MockitoBean CollectionService collections;
  @MockitoBean IndexationService indexations;
  @MockitoBean DocumentService documents;
  @MockitoBean JobService jobs;
  @MockitoBean ProfileService profiles;
  @MockitoBean SearchService search;
  @MockitoBean GenerationJobService generation;
  @MockitoBean VectorRetrievalService retrieval;
  @MockitoBean DvpProperties properties;

  @Test
  void openApiExposesRealQueryParametersInsteadOfInternalQueryMap() throws Exception {
    JsonNode api =
        mapper.readTree(
            mvc.perform(get("/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    JsonNode paths = api.path("paths");
    for (JsonNode path : paths) {
      for (JsonNode operation : path) {
        for (JsonNode parameter : operation.path("parameters")) {
          assertNotEquals("query", parameter.path("name").asText(), operation.toString());
        }
      }
    }
    assertQueryParameter(paths, "/api/v2/collections", "get", "limit", false);
    assertQueryParameter(
        paths, "/api/v2/collections/{collection}/documents/by-external-id", "get", "external_id", true);
    assertQueryParameter(paths, "/api/v2/collections/{collection}/documents", "get", "include", false);
    assertQueryParameter(paths, "/api/v2/embedding-profiles", "get", "cursor", false);
    assertQueryParameter(paths, "/api/v2/jobs", "get", "limit", false);
    assertQueryParameter(paths, "/api/v2/jobs/{id}/errors", "get", "cursor", false);
    assertQueryParameter(paths, "/api/v2/indexations/{indexation}/vectors", "get", "external_id", true);
    assertQueryParameter(paths, "/api/v2/indexations/{indexation}/search", "post", "collection_id", false);
  }

  @Test
  void unknownQueryParametersAreStillRejected() throws Exception {
    var service = mock(SearchService.class);
    var standalone =
        MockMvcBuilders.standaloneSetup(new SearchController(service))
            .setControllerAdvice(new V2ExceptionHandler())
            .build();
    standalone
        .perform(
            post("/api/v2/collections/example/indexations/example/search")
                .param("query", "unexpected")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_request"));
    verifyNoInteractions(service);
  }

  private void assertQueryParameter(JsonNode paths, String path, String method, String name, boolean required) {
    for (JsonNode parameter : paths.path(path).path(method).path("parameters")) {
      if (name.equals(parameter.path("name").asText()) && "query".equals(parameter.path("in").asText())) {
        assertEquals(required, parameter.path("required").asBoolean());
        return;
      }
    }
    fail("Missing query parameter " + name + " on " + method + " " + path);
  }
}
