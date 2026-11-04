package org.lareferencia.embedding.v1.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
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
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lareferencia.embedding.v1.config.ApplicationProperties;
import org.lareferencia.embedding.v1.dto.GenerateEmbeddingsRequest;
import org.lareferencia.embedding.v1.dto.GenerateEmbeddingsResponse;
import org.lareferencia.embedding.v1.entity.StoreEntity;
import org.lareferencia.embedding.v1.exception.EmbeddingProviderException;
import org.lareferencia.embedding.v1.repository.EmbeddingRepository;
import org.lareferencia.embedding.v1.util.PerKeyLockManager;
import org.lareferencia.embedding.v1.util.TextNormalizer;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

        private static final UUID STORE_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        private static final UUID STORE_ID_10 = UUID.fromString("10101010-1010-1010-1010-101010101010");
        private static final UUID STORE_ID_20 = UUID.fromString("20202020-2020-2020-2020-202020202020");

        @Mock
        private StoreService storeService;

        @Mock
        private EmbeddingRepository embeddingRepository;

        @Mock
        private EmbeddingProviderService ollamaEmbeddingService;

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
                StoreEntity store = store(STORE_ID_1, "publications", "nomic-embed-text");
                store.setEmbeddingDimension(3);
                String normalizedText = textNormalizer.normalize("Machine learning applied to repositories");
                String hash = hashService.hash(normalizedText);

                when(storeService.getRequiredStore("nomic-embed-text", 3)).thenReturn(store);
                when(storeService.ensureEmbeddingDimensions(store, 3)).thenReturn(3);
                when(embeddingRepository.findByHashes(eq(STORE_ID_1), eq(3), anyCollection()))
                                .thenReturn(Map.of())
                                .thenReturn(Map.of());
                when(ollamaEmbeddingService.embed(List.of(normalizedText), "nomic-embed-text", 3))
                                .thenReturn(new EmbeddingProviderService.EmbeddingBatchResult(
                                                List.of(new float[] { 0.1f, 0.2f, 0.3f }),
                                                "ollama",
                                                new EmbeddingProviderService.UsageData(8, 8)));
                when(ollamaEmbeddingService.provider()).thenReturn("ollama");
                GenerateEmbeddingsResponse response = service.generateEmbeddings(
                                new GenerateEmbeddingsRequest("nomic-embed-text", 3,
                                                new TextNode("Machine learning applied to repositories")));

                assertNotNull(response);
                assertEquals(STORE_ID_1, response.storeId());
                assertEquals("publications", response.storeName());
                assertEquals("list", response.object());
                assertEquals("nomic-embed-text", response.model());
                assertEquals("ollama", response.provider());
                assertEquals(8, response.usage().promptTokens());
                assertEquals(8, response.usage().totalTokens());
                assertEquals(1, response.data().size());
                assertEquals("embedding", response.data().get(0).object());
                assertEquals(List.of(0.10000000149011612d, 0.20000000298023224d, 0.30000001192092896d),
                                response.data().get(0).embedding());

                verify(ollamaEmbeddingService, times(1)).embed(List.of(normalizedText), "nomic-embed-text", 3);
                verify(embeddingRepository).saveEmbeddings(eq(STORE_ID_1), eq(3), argThat(map -> map.size() == 1
                                && Arrays.equals(map.get(hash), new float[] { 0.1f, 0.2f, 0.3f })));
        }

        @Test
        void reusesCachedEmbeddingsAndDeduplicatesMissingTexts() {
                StoreEntity store = store(STORE_ID_10, "publications", "nomic-embed-text");
                String repeatedText = textNormalizer.normalize("cached text");
                String missingText = textNormalizer.normalize("missing text");
                String cachedHash = hashService.hash(repeatedText);
                String missingHash = hashService.hash(missingText);

                Map<String, float[]> firstLookup = new LinkedHashMap<>();
                firstLookup.put(cachedHash, new float[] { 0.9f, 0.1f });
                store.setEmbeddingDimension(2);

                when(storeService.getRequiredStore("nomic-embed-text", 2)).thenReturn(store);
                when(storeService.ensureEmbeddingDimensions(store, 2)).thenReturn(2);
                when(embeddingRepository.findByHashes(eq(STORE_ID_10), eq(2), anyCollection()))
                                .thenReturn(firstLookup)
                                .thenReturn(Map.of());
                when(ollamaEmbeddingService.embed(List.of(missingText), "nomic-embed-text", 2))
                                .thenReturn(new EmbeddingProviderService.EmbeddingBatchResult(
                                                List.of(new float[] { 0.2f, 0.4f }),
                                                "ollama",
                                                new EmbeddingProviderService.UsageData(5, 5)));
                when(ollamaEmbeddingService.provider()).thenReturn("ollama");

                GenerateEmbeddingsResponse response = service.generateEmbeddings(
                                new GenerateEmbeddingsRequest("nomic-embed-text", 2, JsonNodeFactory.instance.arrayNode()
                                                .add("cached text")
                                                .add("cached text")
                                                .add("missing text")));

                assertNotNull(response);
                assertEquals("list", response.object());
                assertEquals("ollama", response.provider());
                assertEquals(5, response.usage().promptTokens());
                assertEquals(5, response.usage().totalTokens());
                assertEquals(3, response.data().size());
                assertEquals("embedding", response.data().get(0).object());
                assertEquals("embedding", response.data().get(1).object());
                assertEquals("embedding", response.data().get(2).object());
                assertEquals(List.of(0.8999999761581421d, 0.10000000149011612d), response.data().get(0).embedding());
                assertEquals(List.of(0.8999999761581421d, 0.10000000149011612d), response.data().get(1).embedding());
                assertEquals(List.of(0.20000000298023224d, 0.4000000059604645d), response.data().get(2).embedding());

                verify(ollamaEmbeddingService, times(1)).embed(List.of(missingText), "nomic-embed-text", 2);
                verify(embeddingRepository).saveEmbeddings(eq(STORE_ID_10), eq(2), argThat(map -> map.size() == 1
                                && map.containsKey(missingHash)
                                && Arrays.equals(map.get(missingHash), new float[] { 0.2f, 0.4f })));
                verify(embeddingRepository, never()).saveEmbeddings(eq(STORE_ID_10), eq(2),
                                argThat(map -> map.containsKey(cachedHash)));
        }

        @Test
        void rejectsInconsistentEmbeddingDimensionsFromProvider() {
                StoreEntity store = store(STORE_ID_20, "publications", "nomic-embed-text");
                store.setEmbeddingDimension(2);

                when(storeService.getRequiredStore("nomic-embed-text", 2)).thenReturn(store);
                when(embeddingRepository.findByHashes(eq(STORE_ID_20), eq(2), anyCollection()))
                                .thenReturn(Map.of())
                                .thenReturn(Map.of());
                when(ollamaEmbeddingService.embed(
                                List.of(textNormalizer.normalize("alpha"), textNormalizer.normalize("beta")),
                                "nomic-embed-text", 2))
                                .thenReturn(new EmbeddingProviderService.EmbeddingBatchResult(
                                                List.of(new float[] { 0.1f, 0.2f }, new float[] { 0.3f }),
                                                "ollama",
                                                new EmbeddingProviderService.UsageData(0, 0)));
                when(ollamaEmbeddingService.provider()).thenReturn("ollama");

                assertThrows(EmbeddingProviderException.class,
                                () -> service.generateEmbeddings(new GenerateEmbeddingsRequest("nomic-embed-text", 2,
                                                JsonNodeFactory.instance.arrayNode().add("alpha").add("beta"))));

                verify(embeddingRepository, never()).saveEmbeddings(eq(STORE_ID_20), eq(2), anyMap());
        }

        @Test
        void rejectsProviderMismatchBeforeReadingOrWritingCache() {
                StoreEntity incompatible = store(STORE_ID_1, "publications", "model");
                incompatible.setProvider("openai");
                when(storeService.getRequiredStore("model", 3)).thenReturn(incompatible);
                when(ollamaEmbeddingService.provider()).thenReturn("ollama");
                assertThrows(org.lareferencia.embedding.v1.exception.StoreProviderMismatchException.class,
                    () -> service.generateEmbeddings(new GenerateEmbeddingsRequest("model", 3, new TextNode("text"))));
                org.mockito.Mockito.verifyNoInteractions(embeddingRepository);
                verify(ollamaEmbeddingService, never()).embed(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
        }

        private StoreEntity store(UUID id, String name, String model) {
                return StoreEntity.builder()
                                .id(id)
                                .name(name)
                                .provider("ollama")
                                .model(model)
                                .createdAt(Instant.now())
                                .build();
        }
}
