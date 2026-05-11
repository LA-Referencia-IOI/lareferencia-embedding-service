package org.lareferencia.embedding.service;

import java.util.List;

public interface EmbeddingProviderService {

  String providerName();

  List<float[]> embed(List<String> inputs, String model);

  int resolveDimensions(String model);
}