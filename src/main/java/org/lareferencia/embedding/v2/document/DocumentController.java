package org.lareferencia.embedding.v2.document;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.lareferencia.embedding.v2.common.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/collections/{collection}/documents")
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
@Tag(name="v2 Documents",description="Collection-scoped documents with multiple source texts; ingestion never invokes inference")
@ApiResponses({@ApiResponse(responseCode="400",description="Invalid request",content=@Content(schema=@Schema(implementation=V2Error.class))),
    @ApiResponse(responseCode="404",description="Resource not found",content=@Content(schema=@Schema(implementation=V2Error.class))),
    @ApiResponse(responseCode="412",description="If-Match revision conflict",content=@Content(schema=@Schema(implementation=V2Error.class))),
    @ApiResponse(responseCode="413",description="Byte limit exceeded",content=@Content(schema=@Schema(implementation=V2Error.class))),
    @ApiResponse(responseCode="422",description="Invalid document content",content=@Content(schema=@Schema(implementation=V2Error.class))),
    @ApiResponse(responseCode="503",description="Database unavailable",content=@Content(schema=@Schema(implementation=V2Error.class)))})
public class DocumentController {
  private final DocumentService service;
  public DocumentController(DocumentService service) { this.service=service; }

  @PatchMapping("/by-external-id")
  @Operation(summary="Partially upsert a document",description="Metadata replaces the object when present; supplied text keys are upserted and omitted keys remain. Idempotency-Key and quoted If-Match are optional.")
  @Parameter(name="external_id",in=ParameterIn.QUERY,required=true,description="Case-sensitive external identifier, URL encoded")
  @ApiResponses({@ApiResponse(responseCode="200",description="Existing document",content=@Content(schema=@Schema(implementation=DocumentResource.class))),
      @ApiResponse(responseCode="201",description="Created document",content=@Content(schema=@Schema(implementation=DocumentResource.class))),
      @ApiResponse(responseCode="409",description="Idempotency conflict",content=@Content(schema=@Schema(implementation=V2Error.class)))})
  public ResponseEntity<DocumentResponseBody> patch(@PathVariable String collection,@RequestParam MultiValueMap<String,String> query,
      @RequestBody DocumentWriteRequest request,@RequestHeader(value="If-Match",required=false) String ifMatch,
      @RequestHeader(value="Idempotency-Key",required=false) String key) {
    return response(service.write(collection,"PATCH",query,request,ifMatch,key));
  }
  @PutMapping("/by-external-id")
  @Operation(summary="Replace a document snapshot",description="Requires metadata and texts; omitted existing text keys are deleted. Idempotency-Key and quoted If-Match are optional.")
  @Parameter(name="external_id",in=ParameterIn.QUERY,required=true,description="Case-sensitive external identifier, URL encoded")
  @ApiResponses({@ApiResponse(responseCode="200",description="Existing document",content=@Content(schema=@Schema(implementation=DocumentResource.class))),
      @ApiResponse(responseCode="201",description="Created document",content=@Content(schema=@Schema(implementation=DocumentResource.class))),
      @ApiResponse(responseCode="409",description="Idempotency conflict",content=@Content(schema=@Schema(implementation=V2Error.class)))})
  public ResponseEntity<DocumentResponseBody> put(@PathVariable String collection,@RequestParam MultiValueMap<String,String> query,
      @RequestBody DocumentWriteRequest request,@RequestHeader(value="If-Match",required=false) String ifMatch,
      @RequestHeader(value="Idempotency-Key",required=false) String key) {
    return response(service.write(collection,"PUT",query,request,ifMatch,key));
  }
  @GetMapping("/by-external-id")
  @Operation(summary="Get a document by exact external ID")
  @Parameter(name="external_id",in=ParameterIn.QUERY,required=true,description="Case-sensitive external identifier, URL encoded")
  public ResponseEntity<DocumentResource> byExternal(@PathVariable String collection,@RequestParam MultiValueMap<String,String> query) {
    return document(service.getByExternal(collection,query));
  }
  @GetMapping("/{id}")
  @Operation(summary="Get a document by internal UUID")
  public ResponseEntity<DocumentResource> byId(@PathVariable String collection,@PathVariable String id,
      @RequestParam MultiValueMap<String,String> query) { return document(service.getById(collection,id,query)); }

  @GetMapping
  @Operation(summary="List documents",description="Keyset pagination by sequence_id; text bodies are omitted. Metadata is returned only with include=metadata. Pages do not share a snapshot.")
  @Parameters({@Parameter(name="limit",in=ParameterIn.QUERY,schema=@Schema(type="integer",defaultValue="100",minimum="1",maximum="1000")),
      @Parameter(name="cursor",in=ParameterIn.QUERY,description="Opaque next_cursor",schema=@Schema(type="string")),
      @Parameter(name="include",in=ParameterIn.QUERY,description="Optional metadata projection",schema=@Schema(allowableValues={"metadata"}))})
  public PageResponse<DocumentSummary> list(@PathVariable String collection,@RequestParam MultiValueMap<String,String> query) {
    return service.list(collection,query);
  }
  @DeleteMapping("/{id}")
  @Operation(summary="Delete a document and its representations")
  @ApiResponse(responseCode="204",description="Document deleted")
  public ResponseEntity<DocumentResponseBody> delete(@PathVariable String collection,@PathVariable String id,
      @RequestParam MultiValueMap<String,String> query,@RequestHeader(value="If-Match",required=false) String ifMatch,
      @RequestHeader(value="Idempotency-Key",required=false) String key) {
    return response(service.delete(collection,id,query,null,ifMatch,key));
  }
  @DeleteMapping("/{id}/texts")
  @Operation(summary="Delete a source text and its representations")
  @Parameter(name="text_id",in=ParameterIn.QUERY,required=true,description="Exact source-text key, URL encoded")
  @ApiResponse(responseCode="204",description="Text deleted")
  public ResponseEntity<DocumentResponseBody> deleteText(@PathVariable String collection,@PathVariable String id,
      @RequestParam MultiValueMap<String,String> query,@RequestHeader(value="If-Match",required=false) String ifMatch,
      @RequestHeader(value="Idempotency-Key",required=false) String key) {
    // A sentinel distinguishes this route even when text_id is missing; validation returns 400.
    return response(service.delete(collection,id,query,"",ifMatch,key));
  }
  private ResponseEntity<DocumentResource> document(DocumentResource document) {
    return ResponseEntity.ok().eTag(Revisions.etag(document.revision())).body(document);
  }
  private ResponseEntity<DocumentResponseBody> response(IdempotencyService.Result result) {
    var builder=ResponseEntity.status(result.status());
    result.headers().forEach(builder::header);
    return result.status()==204?builder.build():builder.body(new DocumentResponseBody(result.body()));
  }
}
