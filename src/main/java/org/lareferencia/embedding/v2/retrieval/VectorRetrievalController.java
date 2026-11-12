package org.lareferencia.embedding.v2.retrieval;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@io.swagger.v3.oas.annotations.tags.Tag(name="v2 Vector Retrieval")
@RestController
@RequestMapping("/api/v2/collections/{collection}")
@ConditionalOnProperty(name="app.dvp.enabled", havingValue="true")
public class VectorRetrievalController {
  private final VectorRetrievalService service;
  public VectorRetrievalController(VectorRetrievalService service) { this.service=service; }
  @GetMapping("/documents/{document}/vectors")
  @Operation(summary="Retrieve a document's vectors across ready index configurations")
  public Map<String,Object> all(@PathVariable String collection,@PathVariable String document,@RequestParam MultiValueMap<String,String> query) {
    return service.get(collection,null,document,false,query);
  }
  @GetMapping("/document-vectors")
  @Operation(summary="Retrieve vectors across index configurations by external ID")
  public Map<String,Object> allExternal(@PathVariable String collection,@RequestParam MultiValueMap<String,String> query) {
    return service.get(collection,null,null,true,query);
  }
  @GetMapping("/indexations/{indexation}/documents/{document}/vectors")
  @Operation(summary="Retrieve a document's vectors from one index configuration")
  public Map<String,Object> one(@PathVariable String collection,@PathVariable String indexation,@PathVariable String document,@RequestParam MultiValueMap<String,String> query) {
    return service.get(collection,indexation,document,false,query);
  }
  @GetMapping("/indexations/{indexation}/document-vectors")
  @Operation(summary="Retrieve vectors from one index configuration by external ID")
  public Map<String,Object> oneExternal(@PathVariable String collection,@PathVariable String indexation,@RequestParam MultiValueMap<String,String> query) {
    return service.get(collection,indexation,null,true,query);
  }
}
