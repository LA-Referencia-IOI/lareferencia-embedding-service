package org.lareferencia.embedding.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class CorsConfigTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withInitializer(c -> c.getEnvironment().getPropertySources().remove("systemEnvironment"))
          .withBean(org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class)
          .withUserConfiguration(CorsConfig.class);
  @RestController
  static class ExampleController {
    @GetMapping("/api/v2/example")
    String example() {
      return "ok";
    }
  }

  private final org.springframework.test.web.servlet.MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ExampleController())
          .addFilters(
              new CorsConfig()
                  .corsFilter("http://localhost:8990,http://127.0.0.1:8990,http://localhost:5173,http://127.0.0.1:5173")
                  .getFilter())
          .build();

  @Test
  void usesConfiguredLocalOrigins() {
    context.withPropertyValues("app.cors.allowed-origins=http://localhost:8990,http://127.0.0.1:5173").run(c -> {
      assertThat(c).hasNotFailed();
      var filter = c.getBean(FilterRegistrationBean.class).getFilter();
      var mvc = MockMvcBuilders.standaloneSetup(new ExampleController()).addFilters(filter).build();
      try {
        mvc.perform(options("/api/v2/example").header("Origin", "http://127.0.0.1:5173")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isOk());
        mvc.perform(options("/api/v2/example").header("Origin", "http://untrusted.example")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  void configuredOriginsReplaceLocalOrigins() {
    context.withPropertyValues("app.cors.allowed-origins=https://ui.example.org").run(c -> {
      assertThat(c).hasNotFailed();
      var mvc = MockMvcBuilders.standaloneSetup(new ExampleController())
          .addFilters(c.getBean(FilterRegistrationBean.class).getFilter()).build();
      try {
        mvc.perform(options("/api/v2/example").header("Origin", "https://ui.example.org")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isOk());
        mvc.perform(options("/api/v2/example").header("Origin", "http://localhost:8990")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  void missingPropertyAllowsSameOriginAndRejectsCrossOrigin() {
    context.run(c -> {
      assertThat(c).hasNotFailed();
      var mvc = MockMvcBuilders.standaloneSetup(new ExampleController())
          .addFilters(c.getBean(FilterRegistrationBean.class).getFilter()).build();
      try {
        mvc.perform(get("/api/v2/example")).andExpect(status().isOk());
        mvc.perform(get("/api/v2/example").header("Origin", "http://localhost"))
            .andExpect(status().isOk());
        mvc.perform(options("/api/v2/example").header("Origin", "http://localhost:8990")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  void permitsPreflightForRevisionAndIdempotencyHeaders() throws Exception {
    mvc.perform(
            options("/api/v2/example")
                .header("Origin", "http://localhost:8990")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "content-type,if-match,idempotency-key"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8990"))
        .andExpect(header().exists("Access-Control-Allow-Headers"));
  }

  @Test
  void exposesRevisionAndLocationHeaders() throws Exception {
    mvc.perform(get("/api/v2/example").header("Origin", "http://localhost:8990"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8990"))
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
