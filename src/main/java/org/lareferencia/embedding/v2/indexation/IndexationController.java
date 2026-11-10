package org.lareferencia.embedding.v2.indexation;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import org.lareferencia.embedding.v2.common.PageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/collections/{collection}/indexations")
@ConditionalOnExpression("${app.dvp.enabled:false} && !'${app.dvp.role:api}'.equalsIgnoreCase('worker')")
public class IndexationController {
  private final IndexationService service;
  public IndexationController(IndexationService service) { this.service=service; }
  @PostMapping @Operation(summary="Create an immutable index configuration and provisioning job")
  public ResponseEntity<JsonNode> create(@PathVariable String collection,@RequestBody JsonNode body,
      @RequestHeader("Idempotency-Key") String key,@RequestParam MultiValueMap<String,String> query) {
    return response(service.create(collection,body,key,query));
  }
  @GetMapping @Operation(summary="List index configurations")
  public PageResponse<JsonNode> list(@PathVariable String collection,@RequestParam MultiValueMap<String,String> query) {
    return service.list(collection,query);
  }
  @GetMapping("/{id}") @Operation(summary="Get an index configuration")
  public JsonNode get(@PathVariable String collection,@PathVariable String id,@RequestParam MultiValueMap<String,String> query) {
    return service.get(collection,id,query);
  }
  @PostMapping("/{id}/maintenance-jobs") @Operation(summary="Schedule index reconciliation or ANN build")
  public ResponseEntity<JsonNode> maintenance(@PathVariable String collection,@PathVariable String id,
      @RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,@RequestParam MultiValueMap<String,String> query) {
    return response(service.maintenance(collection,id,body,key,query));
  }
  private ResponseEntity<JsonNode> response(org.lareferencia.embedding.v2.common.IdempotencyService.Result result) {
    var response=ResponseEntity.status(result.status());result.headers().forEach(response::header);return response.body(result.body());
  }
}
