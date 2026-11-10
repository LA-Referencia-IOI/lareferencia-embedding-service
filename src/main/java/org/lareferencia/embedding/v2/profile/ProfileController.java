package org.lareferencia.embedding.v2.profile;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.lareferencia.embedding.v2.common.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ProfileService.PATH)
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
@Tag(name="v2 Embedding Profiles",description="Immutable model and preparation identities; credentials remain in runtime configuration")
public class ProfileController {
  private final ProfileService service;
  public ProfileController(ProfileService service) { this.service=service; }
  @PostMapping
  @Operation(summary="Create an immutable profile",description="Requires Idempotency-Key. Does not call an inference provider.")
  @ApiResponses({@ApiResponse(responseCode="201",description="Created profile",content=@Content(schema=@Schema(implementation=ProfileResource.class)),headers=@Header(name="Location")),
      @ApiResponse(responseCode="409",description="Duplicate slug or fingerprint",content=@Content(schema=@Schema(implementation=V2Error.class))),
      @ApiResponse(responseCode="422",description="Unknown provider reference",content=@Content(schema=@Schema(implementation=V2Error.class)))})
  public ResponseEntity<JsonNode> create(@RequestBody ProfileRequest request,@RequestHeader("Idempotency-Key") String key,@RequestParam MultiValueMap<String,String> query) {
    var result=service.create(request,key,query);var response=ResponseEntity.status(result.status());result.headers().forEach(response::header);return response.body(result.body());
  }
  @GetMapping
  @Operation(summary="List profiles",description="UUID keyset pagination; pages do not share a snapshot.")
  @Parameters({@Parameter(name="limit",in=ParameterIn.QUERY,schema=@Schema(type="integer",defaultValue="100",minimum="1",maximum="1000")),@Parameter(name="cursor",in=ParameterIn.QUERY)})
  public PageResponse<ProfileResource> list(@RequestParam MultiValueMap<String,String> query) { return service.list(query); }
  @GetMapping("/{id}")
  @Operation(summary="Get a profile")
  public ProfileResource get(@PathVariable String id,@RequestParam MultiValueMap<String,String> query) { return service.get(id,query); }
}
