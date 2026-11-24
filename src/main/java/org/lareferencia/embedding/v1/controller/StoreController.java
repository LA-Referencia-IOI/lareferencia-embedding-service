package org.lareferencia.embedding.v1.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.lareferencia.embedding.v1.dto.CreateStoreRequest;
import org.lareferencia.embedding.v1.dto.StoreResponse;
import org.lareferencia.embedding.v1.service.StoreService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
@Tag(name = "v1 Stores", description = "Manage embedding stores and their associated embedding models")
public class StoreController {

  private final StoreService storeService;

  @Operation(
      summary = "Create store",
      description =
          "Creates a store and its dedicated vector table. Names allow letters, numbers, spaces, hyphens, underscores, slashes and colons; use the store name as model when requesting embeddings.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Store created successfully"),
    @ApiResponse(responseCode = "400", description = "Invalid payload"),
    @ApiResponse(responseCode = "409", description = "Store name already exists"),
    @ApiResponse(responseCode = "502", description = "Embedding provider failure")
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
