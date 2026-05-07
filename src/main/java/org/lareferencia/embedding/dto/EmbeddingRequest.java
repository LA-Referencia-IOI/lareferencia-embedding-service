package org.lareferencia.embedding.dto;

import java.util.List;

public record EmbeddingRequest(Object input, String model) {
    @SuppressWarnings("unchecked")
    public List<String> inputs() {
        if (input instanceof List) {
            return (List<String>) input;
        } else if (input instanceof String) {
            return List.of((String) input);
        }
        return List.of();
    }
}
