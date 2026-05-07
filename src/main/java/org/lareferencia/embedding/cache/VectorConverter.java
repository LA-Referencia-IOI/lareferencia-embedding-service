package org.lareferencia.embedding.cache;

import com.pgvector.PGvector;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class VectorConverter implements AttributeConverter<float[], String> {

    @Override
    public String convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) {
            return null;
        }
        // Returns string format like "[1.0, 2.0, 3.0]" which Postgres implicitly casts to vector
        return new PGvector(attribute).getValue();
    }

    @Override
    public float[] convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return new PGvector(dbData).toArray();
        } catch (java.sql.SQLException e) {
            throw new RuntimeException("Failed to parse vector from database string", e);
        }
    }
}
