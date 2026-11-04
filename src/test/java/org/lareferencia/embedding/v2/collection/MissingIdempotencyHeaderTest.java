package org.lareferencia.embedding.v2.collection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v2.common.V2ExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MissingIdempotencyHeaderTest {
  @Test
  void namesTheRequiredHeaderWithoutCallingTheService() throws Exception {
    var service = mock(CollectionService.class);
    var mvc = MockMvcBuilders.standaloneSetup(new CollectionController(service))
        .setControllerAdvice(new V2ExceptionHandler())
        .build();

    mvc.perform(post("/api/v2/collections")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"slug":"brasil-v1","name":"Brasil"}
            """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("invalid_request"))
        .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"));
    verifyNoInteractions(service);
  }
}
