package org.lareferencia.embedding.v1.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GenerateEmbeddingsRequestTest {
  @Test
  void readsStoreNameFromModelField() throws Exception {
    var mapper = new ObjectMapper();
    var request =
        mapper.readValue(
            """
            {"model":"embeddinggemma-2 v1","dimensions":768,"input":"example"}
            """,
            GenerateEmbeddingsRequest.class);
    assertEquals("embeddinggemma-2 v1", request.storeName());
    assertEquals(768, request.dimensions());
    org.junit.jupiter.api.Assertions.assertTrue(request.shouldSave());
    org.junit.jupiter.api.Assertions.assertFalse(
        mapper
            .readValue(
                "{\"model\":\"a\",\"dimensions\":3,\"input\":\"x\",\"save\":false}", GenerateEmbeddingsRequest.class)
            .shouldSave());
    assertEquals("example", request.inputs().getFirst());
    assertNull(
        mapper.readValue("{\"dimensions\":768,\"input\":\"example\"}", GenerateEmbeddingsRequest.class).storeName());
  }
}
