package org.lareferencia.embedding.v2.job;

import com.fasterxml.jackson.databind.JsonNode;

/** A handler is registered only once its business operation is implemented. */
public interface JobHandler {
  /** One supported storage kind: maintenance, prepare, generate or import. */
  String kind();
  /** Perform bounded work without keeping database transactions open during external calls. */
  void run(JobRepository.Lease lease,JobRepository repository) throws Exception;
  /** Return a public-only view; never expose credentials, local filesystem paths or source text. */
  JsonNode publicParameters(JsonNode internal);
}
