package org.lareferencia.embedding.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {
  @Bean
  FilterRegistrationBean<CorsFilter> corsFilter(
      @Value("${app.cors.allowed-origins:${CORS_ALLOWED_ORIGINS}}")
          String allowedOrigins) {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(
        Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key", "If-Match"));
    cors.setExposedHeaders(List.of("ETag", "Location", "X-Request-Id", "Retry-After"));
    cors.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    FilterRegistrationBean<CorsFilter> filter = new FilterRegistrationBean<>(new CorsFilter(source));
    filter.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return filter;
  }
}
