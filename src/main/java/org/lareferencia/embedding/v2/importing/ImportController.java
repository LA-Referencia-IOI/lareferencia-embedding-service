package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.UUID;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name="v2 Imports")
@RestController
@RequestMapping("/api/v2/collections/{collection}/imports")
@ConditionalOnExpression("${app.dvp.enabled:false} && ${app.dvp.import-enabled:false} && !'${app.dvp.role:api}'.equalsIgnoreCase('worker')")
public class ImportController {
  private final ImportUploadService uploads;private final ObjectMapper mapper;
  public ImportController(ImportUploadService uploads,ObjectMapper mapper) {
    this.uploads=uploads;this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }
  @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary="Upload a durable JSON or JSONL import",description="Requires Idempotency-Key, a file part and an application/json options part. Processing is asynchronous.")
  @ApiResponse(responseCode="202",description="Durable file and job registered")
  public ResponseEntity<JsonNode> create(@PathVariable String collection,@RequestPart("file") Part file,@RequestPart("options") Part options,
      @RequestHeader("Idempotency-Key") String key,jakarta.servlet.http.HttpServletRequest request) throws IOException,jakarta.servlet.ServletException {
    UUID id;try{id=UUID.fromString(collection);if(!id.toString().equalsIgnoreCase(collection))throw new IllegalArgumentException();}
    catch(RuntimeException e){throw V2Exception.badRequest("Invalid UUID");}
    if(request.getQueryString()!=null && !request.getQueryString().isEmpty())throw V2Exception.badRequest("Unknown query parameter");
    var parts=request.getParts();
    if(parts.size()!=2 || parts.stream().filter(part->part.getName().equals("file")).count()!=1
        || parts.stream().filter(part->part.getName().equals("options")).count()!=1)
      throw V2Exception.badRequest("Provide exactly one file and one options part");
    if(options.getSize()>65536)throw new V2Exception(413,"options_too_large","Import options byte limit exceeded");
    try {
      if(options.getContentType()==null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(options.getContentType())))
        throw new V2Exception(415,"unsupported_media_type","Import options must be application/json");
    } catch(org.springframework.http.InvalidMediaTypeException e) {throw new V2Exception(415,"unsupported_media_type","Import options must be application/json");}
    JsonNode parsed;try(var stream=options.getInputStream()) {
      byte[] bytes=stream.readNBytes(65537);if(bytes.length>65536)throw new V2Exception(413,"options_too_large","Import options byte limit exceeded");
      try {parsed=mapper.readTree(bytes);}catch(com.fasterxml.jackson.core.JacksonException e){throw V2Exception.badRequest("Invalid import options");}
    }
    try(var stream=file.getInputStream()) {
      var result=uploads.upload(id,stream,parsed,key,new org.springframework.util.LinkedMultiValueMap<>());var response=ResponseEntity.status(result.status());
      result.headers().forEach(response::header);return response.body(result.body());
    }
  }
}
