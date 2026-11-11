package org.lareferencia.embedding.v2.indexation;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.headers.Header;
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
  @PostMapping @Operation(summary="Create an immutable index configuration and provisioning job",
      description="Requires an Idempotency-Key. Defaults: cosine, hnsw_vector, empty selection and no chunking. Provisioning runs asynchronously; ANN is built separately.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(type="object"),examples=@ExampleObject(value="{\"slug\":\"abstracts\",\"profile_id\":\"33333333-3333-4333-8333-333333333333\",\"selection\":{\"kinds\":[\"abstract\"],\"languages\":[]},\"chunking\":{\"mode\":\"none\"},\"metric\":\"cosine\",\"ann_strategy\":\"hnsw_vector\"}")))
  @ApiResponses({@ApiResponse(responseCode="202",description="Provisioning job created",headers=@Header(name="Location",description="Index configuration URI")),
      @ApiResponse(responseCode="409",description="Duplicate slug or idempotency conflict"),@ApiResponse(responseCode="422",description="ANN strategy incompatible with profile dimensions")})
  public ResponseEntity<JsonNode> create(@PathVariable String collection,@RequestBody IndexationBody body,
      @RequestHeader("Idempotency-Key") String key,@RequestParam MultiValueMap<String,String> query) {
    return response(service.create(collection,body.value(),key,query));
  }
  @GetMapping @Operation(summary="List index configurations",description="UUID keyset pagination. Pages do not share a snapshot.")
  @Parameters({@Parameter(name="limit",in=ParameterIn.QUERY,schema=@Schema(type="integer",minimum="1",maximum="1000",defaultValue="100")),
      @Parameter(name="cursor",in=ParameterIn.QUERY,description="Opaque cursor from the previous page")})
  public PageResponse<JsonNode> list(@PathVariable String collection,@RequestParam MultiValueMap<String,String> query) {
    return service.list(collection,query);
  }
  @GetMapping("/{id}") @Operation(summary="Get an index configuration")
  public JsonNode get(@PathVariable String collection,@PathVariable String id,@RequestParam MultiValueMap<String,String> query) {
    return service.get(collection,id,query);
  }
  @PostMapping("/{id}/maintenance-jobs") @Operation(summary="Schedule index reconciliation or ANN build",
      description="Requires an Idempotency-Key; inspect the resulting job for completion and errors.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(type="object"),examples={@ExampleObject(name="reconcile",value="{\"operation\":\"reconcile\"}"),@ExampleObject(name="build ANN",value="{\"operation\":\"build_ann\"}")}))
  @ApiResponse(responseCode="202",description="Maintenance job created",headers=@Header(name="Location",description="Job URI"))
  public ResponseEntity<JsonNode> maintenance(@PathVariable String collection,@PathVariable String id,
      @RequestBody IndexationBody body,@RequestHeader("Idempotency-Key") String key,@RequestParam MultiValueMap<String,String> query) {
    return response(service.maintenance(collection,id,body.value(),key,query));
  }
  private ResponseEntity<JsonNode> response(org.lareferencia.embedding.v2.common.IdempotencyService.Result result) {
    var response=ResponseEntity.status(result.status());result.headers().forEach(response::header);return response.body(result.body());
  }
}
