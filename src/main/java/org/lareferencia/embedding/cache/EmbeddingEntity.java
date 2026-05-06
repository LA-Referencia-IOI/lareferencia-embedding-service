package org.lareferencia.embedding.cache;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "embeddings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmbeddingEntity {

    @Id
    @Column(name = "key", nullable = false, length = 64)
    private String key;

    @Column(name = "value", columnDefinition = "bytea", nullable = false)
    private byte[] value;
}
