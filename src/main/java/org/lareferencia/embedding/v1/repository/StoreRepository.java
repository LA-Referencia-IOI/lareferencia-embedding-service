package org.lareferencia.embedding.v1.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.lareferencia.embedding.v1.entity.StoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StoreRepository extends JpaRepository<StoreEntity, UUID> {
  @Query(value = "SELECT EXISTS(SELECT 1 FROM stores WHERE lower(name) = lower(:name))", nativeQuery = true)
  boolean nameExists(String name);

  @Query(value = "SELECT * FROM stores WHERE lower(model) = lower(:model) AND embedding_dimension = :dimensions", nativeQuery = true)
  List<StoreEntity> findByModelAndDimensions(String model, int dimensions);

  Optional<StoreEntity> findByName(String name);
}
