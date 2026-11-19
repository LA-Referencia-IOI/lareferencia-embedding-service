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
@RequestMapping("/api/v2/indexations/{indexation}")
@ConditionalOnProperty(name = "app.dvp.enabled", havingValue = "true")
public class SharedVectorRetrievalController {
  private final VectorRetrievalService service;

  public SharedVectorRetrievalController(VectorRetrievalService service) {
    this.service = service;
  }

  @GetMapping("/documents/{document}/vectors")
  @Operation(summary = "Retrieve document vectors from a shared indexation")
  @Parameters({
    @Parameter(name = "collection_id", in = ParameterIn.QUERY, description = "Optional collection filter"),
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        schema = @Schema(type = "integer", minimum = "1", maximum = "1000")),
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "Cursor requires limit")
  })
  public Map<String, Object> byId(
      @PathVariable String indexation,
      @PathVariable String document,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return service.get(null, indexation, document, false, query);
  }

  @GetMapping("/vectors")
  @Operation(
      summary = "Retrieve document vectors by external ID from a shared indexation",
      description =
          "Searches attached collections; returns 409 ambiguous_external_id when the same external ID is found in multiple collections and collection_id is omitted.")
  @Parameters({
    @Parameter(
        name = "external_id",
        in = ParameterIn.QUERY,
        required = true,
        description = "Exact document external ID"),
    @Parameter(
        name = "collection_id",
        in = ParameterIn.QUERY,
        description = "Optional collection filter; required for ambiguous external IDs"),
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        schema = @Schema(type = "integer", minimum = "1", maximum = "1000")),
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "Cursor requires limit")
  })
  public Map<String, Object> byExternal(
      @PathVariable String indexation, @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return service.get(null, indexation, null, true, query);
  }
}
