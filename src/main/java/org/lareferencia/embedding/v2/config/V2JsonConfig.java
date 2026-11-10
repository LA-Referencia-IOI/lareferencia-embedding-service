package org.lareferencia.embedding.v2.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.lang.reflect.Type;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Strict DTO parsing is scoped to v2, preserving legacy JSON behavior. */
@Configuration
public class V2JsonConfig implements WebMvcConfigurer {
  private final ObjectMapper mapper;
  public V2JsonConfig(ObjectMapper mapper) { this.mapper=mapper; }
  @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    var strict=mapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    strict.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    strict.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Integer)
        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.String, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    converters.addFirst(new MappingJackson2HttpMessageConverter(strict) {
      private boolean v2(Class<?> type) { return type.getName().startsWith("org.lareferencia.embedding.v2."); }
      @Override public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return v2(clazz) && super.canRead(clazz,mediaType);
      }
      @Override public boolean canRead(Type type, Class<?> context, MediaType mediaType) {
        return type instanceof Class<?> clazz && v2(clazz) && super.canRead(type,context,mediaType);
      }
      @Override public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return v2(clazz) && super.canWrite(clazz,mediaType);
      }
      @Override public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
        return v2(clazz) && super.canWrite(type,clazz,mediaType);
      }
    });
  }
}
