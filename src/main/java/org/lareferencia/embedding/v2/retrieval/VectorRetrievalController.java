package org.lareferencia.embedding.v2.retrieval;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "v2 Vector Retrieval")
@RestController
@RequestMapping("/api/v2/collections/{collection}")
@ConditionalOnProperty(name = "app.dvp.enabled", havingValue = "true")
public class VectorRetrievalController {
  private final VectorRetrievalService service;

  public VectorRetrievalController(VectorRetrievalService service) {
    this.service = service;
  }

  @GetMapping("/indexations/{indexation}/documents/{document}/vectors")
  @Operation(summary = "Retrieve a document's vectors from one index configuration")
  @Parameters({
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        description = "Optional page size; without a limit all vectors are returned",
        schema = @Schema(type = "integer", minimum = "1", maximum = "1000")),
    @Parameter(
        name = "cursor",
        in = ParameterIn.QUERY,
        description = "Opaque next_cursor; requires limit",
        schema = @Schema(type = "string"))
  })
  public Map<String, Object> one(
      @PathVariable String collection,
      @PathVariable String indexation,
      @PathVariable String document,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return service.get(collection, indexation, document, false, query);
  }

  @GetMapping("/indexations/{indexation}/vectors")
  @Operation(summary = "Retrieve vectors from one index configuration by external ID")
  @Parameters({
    @Parameter(
        name = "external_id",
        in = ParameterIn.QUERY,
        required = true,
        description = "Exact, case-sensitive document external ID",
        schema = @Schema(type = "string", maxLength = 512)),
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        description = "Optional page size; without a limit all vectors are returned",
        schema = @Schema(type = "integer", minimum = "1", maximum = "1000")),
    @Parameter(
        name = "cursor",
        in = ParameterIn.QUERY,
        description = "Opaque next_cursor; requires limit",
        schema = @Schema(type = "string"))
  })
  public Map<String, Object> oneExternal(
      @PathVariable String collection,
      @PathVariable String indexation,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return service.get(collection, indexation, null, true, query);
  }
}
