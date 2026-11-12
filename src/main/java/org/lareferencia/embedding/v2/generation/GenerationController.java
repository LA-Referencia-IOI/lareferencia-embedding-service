package org.lareferencia.embedding.v2.generation;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.lareferencia.embedding.v2.indexation.IndexationBody;
import org.lareferencia.embedding.v2.preparation.PreparationJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/collections/{collection}/indexations/{indexation}/generation-jobs")
@ConditionalOnExpression("${app.dvp.enabled:false} && !'${app.dvp.role:api}'.equalsIgnoreCase('worker')")
public class GenerationController {
  private final PreparationJobService service;
  public GenerationController(PreparationJobService service) { this.service=service; }
  @PostMapping
  @Operation(summary="Generate missing vectors",description="Requires Idempotency-Key. An empty or absent document_ids list selects the collection; inference runs asynchronously.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@io.swagger.v3.oas.annotations.media.Content(
      schema=@io.swagger.v3.oas.annotations.media.Schema(type="object"),examples=@io.swagger.v3.oas.annotations.media.ExampleObject(value="{\"document_ids\":[]}")))
  @ApiResponse(responseCode="202",description="Generation job queued")
  public ResponseEntity<JsonNode> create(@PathVariable String collection,@PathVariable String indexation,
      @RequestBody(required=false) IndexationBody body,@RequestHeader("Idempotency-Key") String key,
      @RequestParam MultiValueMap<String,String> query) {
    var result=service.create(collection,indexation,body==null?null:body.value(),key,query,"generate");
    var response=ResponseEntity.status(result.status());result.headers().forEach(response::header);return response.body(result.body());
  }
}
