package org.lareferencia.embedding.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class CorsConfigTest {
  @RestController
  static class ExampleController {
    @GetMapping("/api/v2/example")
    String example() {
      return "ok";
    }
  }

  private final org.springframework.test.web.servlet.MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ExampleController())
          .addFilters(new CorsConfig().corsFilter("http://localhost:5173,http://127.0.0.1:5173").getFilter())
          .build();

  @Test
  void permitsPreflightForRevisionAndIdempotencyHeaders() throws Exception {
    mvc.perform(
            options("/api/v2/example")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "content-type,if-match,idempotency-key"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().exists("Access-Control-Allow-Headers"));
  }

  @Test
  void exposesRevisionAndLocationHeaders() throws Exception {
    mvc.perform(get("/api/v2/example").header("Origin", "http://localhost:5173"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().string("Access-Control-Expose-Headers", "ETag, Location, X-Request-Id, Retry-After"));
  }

  @Test
  void rejectsAnUnconfiguredOrigin() throws Exception {
    mvc.perform(
            options("/api/v2/example")
                .header("Origin", "http://untrusted.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }
}
