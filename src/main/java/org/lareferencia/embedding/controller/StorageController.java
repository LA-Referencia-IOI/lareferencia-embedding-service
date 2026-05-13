package org.lareferencia.embedding.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.lareferencia.embedding.dto.CreateStorageRequest;
import org.lareferencia.embedding.dto.StorageResponse;
import org.lareferencia.embedding.service.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/storages")
@RequiredArgsConstructor
@Tag(name = "Storages", description = "Manage embedding storages and their associated embedding models")
public class StorageController {

  private final StorageService storageService;

  @Operation(summary = "Create storage", description = "Creates a new embedding storage. Embedding dimensions and the dedicated pgvector table are initialized lazily during the first successful embeddings generation request.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Storage created successfully"),
      @ApiResponse(responseCode = "400", description = "Invalid payload"),
      @ApiResponse(responseCode = "409", description = "Storage name already exists"),
      @ApiResponse(responseCode = "502", description = "Embedding provider failed during a subsequent embeddings generation request")
  })
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public StorageResponse createStorage(@Valid @RequestBody CreateStorageRequest request) {
    return storageService.createStorage(request);
  }

  @Operation(summary = "List storages", description = "Returns all registered embedding storages.")
  @GetMapping
  public List<StorageResponse> listStorages() {
    return storageService.listStorages();
  }
}