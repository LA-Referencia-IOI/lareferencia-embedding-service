package org.lareferencia.embedding.v2.job;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.lareferencia.embedding.v2.common.PageResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(JobService.PATH)
@ConditionalOnExpression("${app.dvp.enabled:false} && !'${app.dvp.role:api}'.equalsIgnoreCase('worker')")
@Tag(name = "v2 Jobs", description = "Persistent job status, sanitized errors and retries")
public class JobController {
  private final JobService jobs;

  public JobController(JobService jobs) {
    this.jobs = jobs;
  }

  @GetMapping
  @Operation(
      summary = "List persistent jobs",
      description =
          "Ascending UUID keyset pagination. limit defaults to 100 (1–1000); cursor is bound to this route. Pages do not share a snapshot.")
  @Parameters({
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        schema = @Schema(type = "integer", defaultValue = "100", minimum = "1", maximum = "1000")),
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "Opaque cursor from the previous page")
  })
  public PageResponse<JobResource> list(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return jobs.list(query);
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a persistent job")
  public JobResource get(
      @PathVariable String id, @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return jobs.get(id, query);
  }

  @GetMapping("/{id}/errors")
  @Operation(summary = "List job item errors using a bound keyset cursor")
  @Parameters({
    @Parameter(
        name = "limit",
        in = ParameterIn.QUERY,
        schema = @Schema(type = "integer", defaultValue = "100", minimum = "1", maximum = "1000")),
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "Opaque cursor from the previous page")
  })
  public PageResponse<JobErrorResource> errors(
      @PathVariable String id, @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    return jobs.errors(id, query);
  }

  @PostMapping("/{id}/retries")
  @Operation(
      summary = "Retry failed items of a completed job",
      description = "Requires Idempotency-Key. Active originals or unavailable job kinds return 409.")
  public ResponseEntity<JsonNode> retry(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
    var result = jobs.retry(id, key, query);
    var response = ResponseEntity.status(result.status());
    result.headers().forEach(response::header);
    return response.body(result.body());
  }
}
