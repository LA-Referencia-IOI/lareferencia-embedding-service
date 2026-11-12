package org.lareferencia.embedding.v1.controller;

import org.lareferencia.embedding.v1.dto.StatusResponse;
import org.lareferencia.embedding.v1.service.StatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/status")
@RequiredArgsConstructor
@Tag(name = "v1 Status", description = "Inspect service, host, and database resource usage")
public class StatusController {

  private final StatusService statusService;

  @Operation(summary = "Get service status", description = "Returns RAM, CPU, disk, database, store, and embedding table usage statistics.")
  @GetMapping
  public StatusResponse status() {
    return statusService.status();
  }
}
