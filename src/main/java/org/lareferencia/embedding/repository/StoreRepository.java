package org.lareferencia.embedding.repository;

import java.util.UUID;

import org.lareferencia.embedding.entity.StoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<StoreEntity, UUID> {
}