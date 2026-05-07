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
    void testGetEmbeddings_CacheMiss() {
        String input = "hello world";
        String model = "test-model";
        List<Double> mockEmbedding = List.of(0.1, 0.2, 0.3);

        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(client.generateEmbeddings(eq(List.of(input)), eq(model))).thenReturn(List.of(mockEmbedding));

        EmbeddingResponse response = service.getEmbeddings(List.of(input), model);

        assertNotNull(response);
        assertEquals(model, response.model());
        assertEquals("list", response.object());
        assertEquals(1, response.data().size());
        assertEquals(mockEmbedding, response.data().get(0).embedding());
        assertEquals(0, response.data().get(0).index());
        assertEquals("embedding", response.data().get(0).object());

        verify(client, times(1)).generateEmbeddings(anyList(), eq(model));
        verify(cache, times(1)).put(anyString(), eq(mockEmbedding));

        assertEquals(1, meterRegistry.counter("embedding.cache.misses", "model", model).count());
    }

    @Test
    void testGetEmbeddings_MixedCacheAndGeneration() {
        String input1 = "cached";
        String input2 = "missing";
        String model = "test-model";
        List<Double> embedding1 = List.of(0.1);
        List<Double> embedding2 = List.of(0.2);

        // Mock first call (input1) as hit, second call (input2) as miss
        when(cache.get(anyString())).thenReturn(Optional.of(embedding1), Optional.empty());

        when(client.generateEmbeddings(eq(List.of("missing")), eq(model))).thenReturn(List.of(embedding2));

        EmbeddingResponse response = service.getEmbeddings(List.of(input1, input2), model);

        assertNotNull(response);
        assertEquals(2, response.data().size());
        // Verify order preservation and indices
        assertEquals(embedding1, response.data().get(0).embedding());
        assertEquals(0, response.data().get(0).index());
        assertEquals(embedding2, response.data().get(1).embedding());
        assertEquals(1, response.data().get(1).index());

        verify(client, times(1)).generateEmbeddings(eq(List.of("missing")), eq(model));
        verify(cache, times(1)).put(anyString(), eq(embedding2));

        assertEquals(1, meterRegistry.counter("embedding.cache.hits", "model", model).count());
        assertEquals(1, meterRegistry.counter("embedding.cache.misses", "model", model).count());
    }
}
