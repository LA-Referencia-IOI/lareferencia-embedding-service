package org.lareferencia.embedding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lareferencia.embedding.config.ApplicationProperties;
import org.lareferencia.embedding.dto.GenerateEmbeddingsRequest;
import org.lareferencia.embedding.dto.GenerateEmbeddingsResponse;
import org.lareferencia.embedding.entity.EmbeddingStoreEntity;
import org.lareferencia.embedding.repository.EmbeddingRepository;
import org.lareferencia.embedding.util.PerKeyLockManager;
import org.lareferencia.embedding.util.TextNormalizer;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

        @Mock
        private StoreService storeService;

        @Mock
        private EmbeddingRepository embeddingRepository;

        @Mock
        private OllamaEmbeddingService ollamaEmbeddingService;

        private HashService hashService;
        private MeterRegistry meterRegistry;
        private TextNormalizer textNormalizer;
        private ApplicationProperties applicationProperties;
        private EmbeddingService service;

        @BeforeEach
        void setUp() {
                hashService = new HashService();
                meterRegistry = new SimpleMeterRegistry();
                textNormalizer = new TextNormalizer();
                applicationProperties = new ApplicationProperties();
                applicationProperties.setMaxBatchSize(8);
                applicationProperties.setMaxInputLength(2000);
                service = new EmbeddingService(
                                storeService,
                                embeddingRepository,
                                hashService,
                                ollamaEmbeddingService,
                                meterRegistry,
                                textNormalizer,
                                applicationProperties,
                                new PerKeyLockManager());
        }

        @Test
        void generatesAndCachesMissingEmbeddings() {
                EmbeddingStoreEntity store = store(1L, "publications", "nomic-embed-text");
                String normalizedText = textNormalizer.normalize("Machine learning applied to repositories");
                String hash = hashService.hash(normalizedText);

                when(storeService.getRequiredStore(1L)).thenReturn(store);
                when(storeService.ensureEmbeddingDimensions(store, 3)).thenReturn(3);
                when(embeddingRepository.findByHashes(eq(1L), eq(null), anyCollection()))
                                .thenReturn(Map.of())
                                .thenReturn(Map.of());
                when(ollamaEmbeddingService.embed(List.of(normalizedText), "nomic-embed-text"))
                                .thenReturn(List.of(new float[] { 0.1f, 0.2f, 0.3f }));
                GenerateEmbeddingsResponse response = service.generateEmbeddings(
                                new GenerateEmbeddingsRequest(1L,
                                                new TextNode("Machine learning applied to repositories")));

                assertNotNull(response);
                assertEquals(1L, response.storeId());
                assertEquals("publications", response.storeName());
                assertEquals("nomic-embed-text", response.model());
                assertEquals(1, response.data().size());
                assertEquals(List.of(0.10000000149011612d, 0.20000000298023224d, 0.30000001192092896d),
                                response.data().get(0).embedding());

                verify(ollamaEmbeddingService, times(1)).embed(List.of(normalizedText), "nomic-embed-text");
                verify(embeddingRepository).saveEmbeddings(eq(1L), eq(3), argThat(map -> map.size() == 1
                                && Arrays.equals(map.get(hash), new float[] { 0.1f, 0.2f, 0.3f })));
        }

        @Test
        void reusesCachedEmbeddingsAndDeduplicatesMissingTexts() {
                EmbeddingStoreEntity store = store(10L, "publications", "nomic-embed-text");
                String repeatedText = textNormalizer.normalize("cached text");
                String missingText = textNormalizer.normalize("missing text");
                String cachedHash = hashService.hash(repeatedText);
                String missingHash = hashService.hash(missingText);

                Map<String, float[]> firstLookup = new LinkedHashMap<>();
                firstLookup.put(cachedHash, new float[] { 0.9f, 0.1f });
                store.setEmbeddingDimensions(2);

                when(storeService.getRequiredStore(10L)).thenReturn(store);
                when(storeService.ensureEmbeddingDimensions(store, 2)).thenReturn(2);
                when(embeddingRepository.findByHashes(eq(10L), eq(2), anyCollection()))
                                .thenReturn(firstLookup)
                                .thenReturn(Map.of());
                when(ollamaEmbeddingService.embed(List.of(missingText), "nomic-embed-text"))
                                .thenReturn(List.of(new float[] { 0.2f, 0.4f }));

                GenerateEmbeddingsResponse response = service.generateEmbeddings(
                                new GenerateEmbeddingsRequest(10L, JsonNodeFactory.instance.arrayNode()
                                                .add("cached text")
                                                .add("cached text")
                                                .add("missing text")));

                assertNotNull(response);
                assertEquals(3, response.data().size());
                assertEquals(List.of(0.8999999761581421d, 0.10000000149011612d), response.data().get(0).embedding());
                assertEquals(List.of(0.8999999761581421d, 0.10000000149011612d), response.data().get(1).embedding());
                assertEquals(List.of(0.20000000298023224d, 0.4000000059604645d), response.data().get(2).embedding());

                verify(ollamaEmbeddingService, times(1)).embed(List.of(missingText), "nomic-embed-text");
                verify(embeddingRepository).saveEmbeddings(eq(10L), eq(2), argThat(map -> map.size() == 1
                                && map.containsKey(missingHash)
                                && Arrays.equals(map.get(missingHash), new float[] { 0.2f, 0.4f })));
                verify(embeddingRepository, never()).saveEmbeddings(eq(10L), eq(2),
                                argThat(map -> map.containsKey(cachedHash)));
        }

        private EmbeddingStoreEntity store(Long id, String name, String model) {
                return EmbeddingStoreEntity.builder()
                                .id(id)
                                .name(name)
                                .model(model)
                                .createdAt(Instant.now())
                                .build();
        }
}
