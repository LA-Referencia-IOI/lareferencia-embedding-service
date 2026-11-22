package org.lareferencia.embedding.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpaControllerTest {
  private final org.springframework.test.web.servlet.MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new SpaController()).build();

  @Test
  void forwardsKnownSpaRoutesAndNestedRoutes() throws Exception {
    mvc.perform(get("/search").accept("text/html")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    mvc.perform(get("/collections/example").accept("text/html"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
  }

  @Test
  void doesNotForwardApisAndAssets() throws Exception {
    for (String path :
        new String[] {
          "/api/v2/missing",
          "/api-docs",
          "/actuator/health",
          "/swagger-ui/index.html",
          "/assets/missing.js",
          "/collections/missing.js"
        }) {
      mvc.perform(get(path).accept("text/html")).andExpect(status().isNotFound());
    }
  }
}
