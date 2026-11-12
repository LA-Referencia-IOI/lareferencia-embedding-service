package org.lareferencia.embedding.v2.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/collections/{collection}/indexations/{indexation}/search")
@ConditionalOnExpression("${app.dvp.enabled:false} && !'${app.dvp.role:api}'.equalsIgnoreCase('worker')")
public class SearchController {
  private final SearchService service;
  public SearchController(SearchService service) {this.service=service;}
  @PostMapping
  @Operation(summary="Search one index configuration",description="Exact or approximate vector/text search; optional texts and metadata projections.")
  public Map<String,Object> search(@PathVariable String collection,@PathVariable String indexation,@RequestBody JsonNode request,@RequestParam MultiValueMap<String,String> query) {
    if(!query.isEmpty())throw org.lareferencia.embedding.v2.common.V2Exception.badRequest("Unknown query parameter");
    return service.search(collection,indexation,request);
  }
}
