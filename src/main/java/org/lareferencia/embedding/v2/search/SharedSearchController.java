package org.lareferencia.embedding.v2.search;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name="v2 Search")
@RestController
@RequestMapping("/api/v2/indexations/{indexation}/search")
@ConditionalOnExpression("${app.dvp.enabled:false} && !'${app.dvp.role:api}'.equalsIgnoreCase('worker')")
public class SharedSearchController {
  private final SearchService service;
  public SharedSearchController(SearchService service) {this.service=service;}
  @PostMapping
  @Operation(summary="Search a shared indexation",description="Searches all attached collections by default. The optional collection_id query parameter restricts results to one attached collection. Each result includes its collection_id.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(type="object"),examples=@ExampleObject(value="{\"query\":{\"vector\":[0.1,0.2,0.3]},\"mode\":\"exact\",\"top_n\":10,\"result_unit\":\"document\"}")))
  public Map<String,Object> search(@PathVariable String indexation,@RequestParam(required=false,name="collection_id") String collection,
      @RequestBody JsonNode request,jakarta.servlet.http.HttpServletRequest http) {
    if(http.getParameterMap().entrySet().stream().anyMatch(e->!e.getKey().equals("collection_id") || e.getValue().length!=1))
      throw org.lareferencia.embedding.v2.common.V2Exception.badRequest("Unknown or repeated query parameter");
    return service.search(collection,indexation,request);
  }
}
