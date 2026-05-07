package org.lareferencia.embedding.dto;

import java.util.List;

/**
 * DTO representing an embedding response, aligned with OpenAI API contract.
 */
public record EmbeddingResponse(
        String object,
        List<EmbeddingData> data,
        String model,
        UsageData usage
) {
    public record EmbeddingData(
            String object,
            List<Double> embedding,
            int index
    ) {
        public EmbeddingData(List<Double> embedding, int index) {
            this("embedding", embedding, index);
        }
    }

    public record UsageData(
            int prompt_tokens,
            int total_tokens
    ) {
    }

    public EmbeddingResponse(String model, List<EmbeddingData> data) {
        this("list", data, model, new UsageData(0, 0));
    }
}
