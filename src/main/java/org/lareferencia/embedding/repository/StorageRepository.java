package org.lareferencia.embedding.repository;

import java.util.UUID;

import org.lareferencia.embedding.entity.StorageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageRepository extends JpaRepository<StorageEntity, UUID> {

  boolean existsByNameIgnoreCase(String name);
}