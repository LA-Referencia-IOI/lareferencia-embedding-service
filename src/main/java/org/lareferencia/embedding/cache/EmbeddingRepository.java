package org.lareferencia.embedding.cache;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface EmbeddingRepository extends JpaRepository<EmbeddingEntity, String> {

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO embeddings (hash_key, embedding, created_at) " +
                   "VALUES (:hashKey, CAST(:embedding AS vector), :createdAt) " +
                   "ON CONFLICT (hash_key) DO UPDATE SET " +
                   "embedding = EXCLUDED.embedding, " +
                   "created_at = EXCLUDED.created_at", nativeQuery = true)
    void saveWithCast(@Param("hashKey") String hashKey, 
                      @Param("embedding") String embedding, 
                      @Param("createdAt") LocalDateTime createdAt);
}
