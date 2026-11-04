package org.lareferencia.embedding.v2.collection;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateCollectionRequest(
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED, maxLength=100, pattern="^[a-z0-9][a-z0-9_-]*$", example="research") String slug,
    @Schema(requiredMode=Schema.RequiredMode.REQUIRED, maxLength=200, description="Display name, at most 200 Unicode code points; case and Unicode preserved; need not be unique", example="Investigación Abierta") String name) {}
