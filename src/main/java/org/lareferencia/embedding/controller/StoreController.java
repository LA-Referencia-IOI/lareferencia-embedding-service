package org.lareferencia.embedding.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.lareferencia.embedding.dto.CreateStoreRequest;
import org.lareferencia.embedding.dto.StoreResponse;
import org.lareferencia.embedding.service.StoreService;
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
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
@Tag(name = "Stores", description = "Manage embedding stores and their associated embedding models")
public class StoreController {

  private final StoreService storeService;

  @Operation(summary = "Create store", description = "Creates a new embedding store. Embedding dimensions and the dedicated pgvector table are initialized lazily during the first successful embeddings generation request.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Store created successfully"),
      @ApiResponse(responseCode = "400", description = "Invalid payload"),
      @ApiResponse(responseCode = "409", description = "Store name already exists"),
      @ApiResponse(responseCode = "502", description = "Embedding provider failed during a subsequent embeddings generation request")
  })
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public StoreResponse createStore(@Valid @RequestBody CreateStoreRequest request) {
    return storeService.createStore(request);
  }

  @Operation(summary = "List stores", description = "Returns all registered embedding stores.")
  @GetMapping
  public List<StoreResponse> listStores() {
    return storeService.listStores();
  }
}