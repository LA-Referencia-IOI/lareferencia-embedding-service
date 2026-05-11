package org.lareferencia.embedding.repository;

import org.lareferencia.embedding.entity.EmbeddingStoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<EmbeddingStoreEntity, Long> {

  boolean existsByNameIgnoreCase(String name);
}