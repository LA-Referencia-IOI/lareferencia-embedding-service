package org.lareferencia.embedding.v2.collection;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.lareferencia.embedding.v2.common.PageResponse;
import org.lareferencia.embedding.v2.common.V2Error;
import org.lareferencia.embedding.v2.indexation.IndexationBody;
import org.lareferencia.embedding.v2.indexation.IndexationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(CollectionService.PATH)
@ConditionalOnProperty(name = "app.dvp.enabled", havingValue = "true")
@Tag(name = "v2 Collections", description = "Collections organize documents; they do not provide tenant authorization")
@ApiResponses({
  @ApiResponse(
      responseCode = "400",
      description = "Invalid request",
      content = @Content(schema = @Schema(implementation = V2Error.class))),
  @ApiResponse(
      responseCode = "503",
      description = "Database or pool unavailable",
      content = @Content(schema = @Schema(implementation = V2Error.class)))
})
public class CollectionController {
  private final CollectionService service;
  private final IndexationService indexations;

  public CollectionController(CollectionService service, IndexationService indexations) {
    this.service = service;
    this.indexations = indexations;
  }

  @PostMapping
  @Operation(
      summary = "Create an immutable collection",
      description =
          "Requires Idempotency-Key. A matching replay returns the original status, body and Location. Unknown fields are rejected.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Collection created",
        content = @Content(schema = @Schema(implementation = CollectionResource.class)),
        headers = @Header(name = "Location", description = "Collection URI")),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid fields or missing idempotency key",
        content = @Content(schema = @Schema(implementation = V2Error.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Duplicate slug, key conflict or key still in progress",
        content = @Content(schema = @Schema(implementation = V2Error.class))),
    @ApiResponse(
        responseCode = "413",
        description = "Request byte limit exceeded",
        content = @Content(schema = @Schema(implementation = V2Error.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Database unavailable",
        content = @Content(schema = @Schema(implementation = V2Error.class)))
  })
  public ResponseEntity<JsonNode> create(
      @RequestBody CreateCollectionRequest request,
      @RequestHeader("Idempotency-Key") String key,
      @RequestParam MultiValueMap<String, String> query) {
    var result = service.create(request, key, query);
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(response::header);
    return response.body(result.body());
  }

  @GetMapping
  @Operation(
      summary = "List collections",
      description =
          "Ascending UUID keyset pagination. limit defaults to 100 (1–1000); cursor is opaque and bound to this route. Pages do not share a snapshot; stabilize ingestion to enumerate a changing collection registry.")
  @Parameters({
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        schema = @Schema(type = "integer", defaultValue = "100", minimum = "1", maximum = "1000")),
    @Parameter(
        name = "cursor",
        in = ParameterIn.QUERY,
        description = "Opaque next_cursor from the previous page",
        schema = @Schema(type = "string"))
  })
  public PageResponse<CollectionResource> list(@RequestParam MultiValueMap<String, String> query) {
    return service.list(query);
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Get a collection",
      description = "Includes exact counts of documents and indexations in all states.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Collection"),
    @ApiResponse(
        responseCode = "404",
        description = "Collection absent",
        content = @Content(schema = @Schema(implementation = V2Error.class)))
  })
  public CollectionDetails get(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) {
    return service.get(id, query);
  }

  @GetMapping("/{collection}/indexations")
  @Operation(
      summary = "List indexations attached to a collection",
      description = "An indexation can be attached to multiple collections.")
  @Parameters({
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        schema = @Schema(type = "integer", minimum = "1", maximum = "1000", defaultValue = "100")),
    @Parameter(name = "cursor", in = ParameterIn.QUERY)
  })
  public PageResponse<JsonNode> indexations(
      @PathVariable String collection, @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return indexations.list(collection, query);
  }

  @PostMapping("/{collection}/indexations")
  @Operation(
      summary = "Create and attach an indexation",
      description =
          "Creates an indexation, attaches it to the collection, and schedules provisioning. Requires Idempotency-Key.")
  @ApiResponse(responseCode = "202", description = "Provisioning job created")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content =
          @Content(
              schema = @Schema(type = "object"),
              examples =
                  @io.swagger.v3.oas.annotations.media.ExampleObject(
                      value =
                          "{\"slug\":\"abstracts\",\"profile_id\":\"33333333-3333-4333-8333-333333333333\",\"metric\":\"cosine\",\"ann_strategy\":\"hnsw_vector\"}")))
  public ResponseEntity<JsonNode> createIndexation(
      @PathVariable String collection,
      @RequestBody IndexationBody body,
      @RequestHeader("Idempotency-Key") String key,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    var result = indexations.create(collection, body.value(), key, query);
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(response::header);
    return response.body(result.body());
  }

  @GetMapping("/{collection}/indexations/{id}")
  @Operation(summary = "Get an indexation attached to a collection")
  public JsonNode indexation(
      @PathVariable String collection,
      @PathVariable String id,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return indexations.get(collection, id, query);
  }

  @PutMapping("/{collection}/indexations/{id}")
  @Operation(
      summary = "Attach an indexation to a collection",
      description = "Idempotently creates the association; this does not generate vectors.")
  @ApiResponse(responseCode = "204", description = "Indexation attached")
  public ResponseEntity<Void> attachIndexation(
      @PathVariable String collection,
      @PathVariable String id,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    indexations.attach(collection, id, query);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{collection}/indexations/{id}/maintenance-jobs")
  @Operation(summary = "Schedule index maintenance")
  @ApiResponse(responseCode = "202", description = "Maintenance job created")
  public ResponseEntity<JsonNode> maintenance(
      @PathVariable String collection,
      @PathVariable String id,
      @RequestBody IndexationBody body,
      @RequestHeader("Idempotency-Key") String key,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    var result = indexations.maintenance(collection, id, body.value(), key, query);
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(response::header);
    return response.body(result.body());
  }
}
