package org.lareferencia.embedding.v2.preparation;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.lareferencia.embedding.v2.indexation.IndexationBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name="v2 Preparation")
@RestController
@RequestMapping("/api/v2/collections/{collection}/indexations/{indexation}/preparation-jobs")
@ConditionalOnExpression("${app.dvp.enabled:false} && !'${app.dvp.role:api}'.equalsIgnoreCase('worker')")
public class PreparationController {
  private final PreparationJobService service;
  public PreparationController(PreparationJobService service) { this.service=service; }
  @PostMapping
  @Operation(summary="Prepare source text units without inference",description="Requires Idempotency-Key. Omit document_ids or use an empty array to enumerate the collection.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(content=@io.swagger.v3.oas.annotations.media.Content(
      schema=@io.swagger.v3.oas.annotations.media.Schema(type="object"),examples=@io.swagger.v3.oas.annotations.media.ExampleObject(value="{\"document_ids\":[]}")))
  @ApiResponse(responseCode="202",description="Preparation job queued")
  public ResponseEntity<JsonNode> create(@PathVariable String collection,@PathVariable String indexation,
      @RequestBody(required=false) IndexationBody body,@RequestHeader("Idempotency-Key") String key,
      @RequestParam MultiValueMap<String,String> query) {
    var result=service.create(collection,indexation,body==null?null:body.value(),key,query);
    var response=ResponseEntity.status(result.status());result.headers().forEach(response::header);return response.body(result.body());
  }
}
