package org.lareferencia.embedding.v2.indexation;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.lareferencia.embedding.v2.collection.CollectionResource;
import org.lareferencia.embedding.v2.common.PageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/indexations")
@ConditionalOnExpression("${app.dvp.enabled:false} && !'${app.dvp.role:api}'.equalsIgnoreCase('worker')")
@Tag(name = "v2 Indexations")
public class IndexationController {
  private final IndexationService service;

  public IndexationController(IndexationService service) {
    this.service = service;
  }

  @PostMapping
  @Operation(
      summary = "Create an indexation",
      description =
          "Creates an independent indexation and queues a provisioning job. No collection is attached. Requires Idempotency-Key; wait for state=ready before generating or importing vectors.")
  @ApiResponse(responseCode = "202", description = "Indexation and provisioning job created")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content =
          @Content(
              schema = @Schema(type = "object"),
              examples =
                  @ExampleObject(
                      value =
                          "{\"slug\":\"abstracts\",\"profile_id\":\"33333333-3333-4333-8333-333333333333\",\"metric\":\"cosine\",\"ann_strategy\":\"hnsw_vector\"}")))
  public ResponseEntity<JsonNode> create(
      @RequestBody IndexationBody body,
      @RequestHeader("Idempotency-Key") String key,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    var result = service.create(null, body.value(), key, query);
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(response::header);
    return response.body(result.body());
  }

  @GetMapping
  @Operation(
      summary = "List all indexations",
      description = "Optionally filter by an attached collection. Results include all attached collection IDs.")
  @Parameters({
    @Parameter(
        name = "collection_id",
        in = ParameterIn.QUERY,
        description = "Optional attached collection UUID",
        schema = @Schema(type = "string", format = "uuid")),
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        schema = @Schema(type = "integer", minimum = "1", maximum = "1000", defaultValue = "100")),
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "Opaque cursor from the previous page")
  })
  public PageResponse<JsonNode> list(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return service.listGlobal(query);
  }

  @GetMapping("/{indexation}")
  @Operation(
      summary = "Get an indexation",
      description = "Returns an independent indexation. Use /collections to list its attached collections.")
  public JsonNode get(
      @PathVariable String indexation, @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return service.getGlobal(indexation, query);
  }

  @GetMapping("/{indexation}/collections")
  @Operation(summary = "List collections attached to an indexation")
  @Parameters({
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        schema = @Schema(type = "integer", minimum = "1", maximum = "1000", defaultValue = "100")),
    @Parameter(name = "cursor", in = ParameterIn.QUERY)
  })
  public PageResponse<CollectionResource> collections(
      @PathVariable String indexation, @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return service.collections(indexation, query);
  }

  @PostMapping("/{indexation}/collections")
  @Operation(
      summary = "Attach a collection to an indexation",
      description =
          "The body contains a collection_id UUID. Repeating the association has no effect; this does not generate vectors.")
  @ApiResponse(responseCode = "204", description = "Collection attached")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content =
          @Content(
              schema =
                  @Schema(
                      type = "object",
                      requiredProperties = {"collection_id"}),
              examples = @ExampleObject(value = "{\"collection_id\":\"11111111-1111-4111-8111-111111111111\"}")))
  public ResponseEntity<Void> attachCollection(
      @PathVariable String indexation,
      @RequestBody JsonNode body,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    if (!body.isObject() || body.size() != 1 || !body.path("collection_id").isTextual())
      throw org.lareferencia.embedding.v2.common.V2Exception.badRequest("Expected collection_id");
    service.attach(body.path("collection_id").textValue(), indexation, query);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{indexation}/collections/{collection}")
  @Operation(
      summary = "Attach a collection to an indexation",
      description = "Idempotently creates the association; this does not generate vectors.")
  @ApiResponse(responseCode = "204", description = "Collection attached")
  public ResponseEntity<Void> attach(
      @PathVariable String indexation,
      @PathVariable String collection,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    service.attach(collection, indexation, query);
    return ResponseEntity.noContent().build();
  }
}
