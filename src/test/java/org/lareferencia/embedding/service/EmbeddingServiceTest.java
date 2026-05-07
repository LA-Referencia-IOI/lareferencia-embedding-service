package org.lareferencia.embedding.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.cache.EmbeddingCache;
import org.lareferencia.embedding.client.EmbeddingClient;
import org.lareferencia.embedding.dto.EmbeddingResponse;
import org.lareferencia.embedding.util.TextNormalizer;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmbeddingServiceTest {

    private EmbeddingCache cache;
    private EmbeddingClient client;
    private MeterRegistry meterRegistry;
    private TextNormalizer textNormalizer;
    private EmbeddingService service;

    @BeforeEach
    void setUp() {
        cache = mock(EmbeddingCache.class);
        client = mock(EmbeddingClient.class);
        meterRegistry = new SimpleMeterRegistry();
        textNormalizer = new TextNormalizer();
        service = new EmbeddingService(cache, client, meterRegistry, textNormalizer);
    }

    @Test
    void testGetEmbedding_CacheMiss() {
        String input = "hello world";
        String model = "test-model";
        List<Double> mockEmbedding = List.of(0.1, 0.2, 0.3);

        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(client.generateEmbedding(eq(input), eq(model))).thenReturn(mockEmbedding);

        EmbeddingResponse response = service.getEmbedding(input, model);

        assertNotNull(response);
        assertEquals(model, response.model());
        assertEquals(mockEmbedding, response.embedding());

        verify(client, times(1)).generateEmbedding(input, model);
        verify(cache, times(1)).put(anyString(), eq(mockEmbedding));

        assertEquals(1, meterRegistry.counter("embedding.cache.misses", "model", model).count());
    }

    @Test
    void testGetEmbedding_CacheHit() {
        String input = "hello world";
        String model = "test-model";
        List<Double> mockEmbedding = List.of(0.1, 0.2, 0.3);

        when(cache.get(anyString())).thenReturn(Optional.of(mockEmbedding));

        EmbeddingResponse response = service.getEmbedding(input, model);

        assertNotNull(response);
        assertEquals(model, response.model());
        assertEquals(mockEmbedding, response.embedding());

        verify(client, never()).generateEmbedding(anyString(), anyString());

        assertEquals(1, meterRegistry.counter("embedding.cache.hits", "model", model).count());
    }
}
