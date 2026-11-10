package org.lareferencia.embedding.v2.profile;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.lareferencia.embedding.v2.common.V2Exception;

class ProviderRegistryTest {
  private HttpServer server;
  private ThreadPoolExecutor executor;
  private ProviderRegistry registry;
  private final AtomicInteger first=new AtomicInteger(),second=new AtomicInteger();
  private final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  @BeforeEach void setup() throws Exception {
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/first",exchange->{first.incrementAndGet();exchange.getRequestBody().readAllBytes();
      byte[] response="{\"data\":[{\"index\":1,\"embedding\":[3,4]},{\"index\":0,\"embedding\":[1,2]}]}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});
    server.createContext("/second",exchange->{second.incrementAndGet();exchange.getRequestBody().readAllBytes();
      byte[] response="{\"embeddings\":[[5,6],[7,8]]}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});
    server.createContext("/invalid",exchange->{byte[] response="{\"data\":[{\"index\":0,\"embedding\":[1,2]},{\"index\":0,\"embedding\":[1,2]}]}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});
    server.createContext("/rejected",exchange->{byte[] response="sensitive-provider-detail".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(400,response.length);exchange.getResponseBody().write(response);exchange.close();});
    server.createContext("/slow",exchange->{entered.countDown();try {release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();});
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.start();
    var p=new ProviderProperties();var map=new LinkedHashMap<String,ProviderProperties.Provider>();
    for(String name:List.of("first","second","invalid","slow","rejected","secret")) {
      var provider=new ProviderProperties.Provider();provider.setType(name.equals("second")?"OLLAMA":"OPENAI_COMPATIBLE");
      provider.setEndpoint("http://127.0.0.1:"+server.getAddress().getPort()+"/"+(name.equals("secret")?"first":name));provider.setConcurrency(1);
      if(name.equals("secret")) provider.setSecretRef("DVP_TEST_MISSING_SECRET_REFERENCE");
      provider.setRequestTimeout(Duration.ofSeconds(1));provider.setBatchSize(2);map.put(name,provider);
    }
    p.setProviders(map);executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>());
    registry=new ProviderRegistry(p,executor,new ObjectMapper());
  }
  @AfterEach void close() { release.countDown();server.stop(0);executor.shutdownNow(); }
  private ProfileResource profile(String ref,String mode) { return new ProfileResource(UUID.randomUUID(),"slug","model","revision",2,mode,ref,
      new ProfileRequest.Preparation("identity-v1","passage: ","query: "),"0".repeat(64),"http".equals(mode),Instant.now()); }
  @Test void routesDistinctProvidersAndRestoresIndexedOrder() {
    var one=registry.embed(profile("first","http"),List.of("a","b"));
    assertArrayEquals(new float[]{1,2},one.get(0));assertArrayEquals(new float[]{3,4},one.get(1));
    var two=registry.embed(profile("second","http"),List.of("a","b"));
    assertArrayEquals(new float[]{5,6},two.get(0));assertArrayEquals(new float[]{7,8},two.get(1));
    assertEquals(1,first.get());assertEquals(1,second.get());
  }
  @Test void rejectsBadResponsesImportOnlyAndOversizedBatches() {
    assertEquals("invalid_provider_response",assertThrows(V2Exception.class,()->registry.embed(profile("invalid","http"),List.of("a","b"))).code());
    assertEquals("profile_not_executable",assertThrows(V2Exception.class,()->registry.embed(profile(null,"import_only"),List.of("a"))).code());
    assertEquals(422,assertThrows(V2Exception.class,()->registry.embed(profile("first","http"),List.of("a","b","c"))).status());
    var rejection=assertThrows(V2Exception.class,()->registry.embed(profile("rejected","http"),List.of("private-source")));
    assertEquals("provider_rejected",rejection.code());assertFalse(rejection.getMessage().contains("sensitive-provider-detail"));assertFalse(rejection.getMessage().contains("private-source"));
    var secret=assertThrows(V2Exception.class,()->registry.embed(profile("secret","http"),List.of("private-source")));
    assertEquals("provider_unavailable",secret.code());assertFalse(secret.getMessage().contains("DVP_TEST_MISSING_SECRET_REFERENCE"));assertEquals(0,first.get());
  }
  @Test void blocksAdditionalCallsAtProviderLimitAndTimesOut() throws Exception {
    try(var callers=Executors.newVirtualThreadPerTaskExecutor()) {
      var pending=callers.submit(()->assertThrows(V2Exception.class,()->registry.embed(profile("slow","http"),List.of("a"))));
      assertTrue(entered.await(2,TimeUnit.SECONDS));
      assertEquals("inference_busy",assertThrows(V2Exception.class,()->registry.embed(profile("slow","http"),List.of("b"))).code());
      assertEquals("provider_timeout",pending.get(5,TimeUnit.SECONDS).code());
    }
  }
  @Test void exhaustedSharedExecutorRejectsWithoutCallingAProvider() throws Exception {
    var started=new CountDownLatch(2);var done=new CountDownLatch(1);
    Runnable block=()->{started.countDown();try {done.await(3,TimeUnit.SECONDS);} catch(InterruptedException e){Thread.currentThread().interrupt();}};
    executor.execute(block);executor.execute(block);
    try {
      assertTrue(started.await(2,TimeUnit.SECONDS));
      assertEquals("inference_busy",assertThrows(V2Exception.class,()->registry.embed(profile("first","http"),List.of("a","b"))).code());
      assertEquals(0,first.get());
    } finally {done.countDown();}
  }
  @Test void preparationUsesFrozenLegacyRecipeAndDistinctPrefixes() {
    var prep=new ProfilePreparation();var p=profile("first","http");
    assertEquals("passage: Multi-\nLingual",prep.document(p,"Multi-\nLingual",100));
    var legacy=new ProfileResource(p.id(),p.slug(),p.model(),p.modelRevision(),p.dimensions(),p.executionMode(),p.providerRef(),
        new ProfileRequest.Preparation("legacy-normalizer-v1","d: ","q: "),p.fingerprint(),true,p.createdAt());
    assertEquals("d: multilingual",prep.document(legacy,"Multi-\nLingual",100));assertEquals("q: multilingual",prep.query(legacy,"Multi-\nLingual",100));
    assertEquals(422,assertThrows(V2Exception.class,()->prep.query(legacy,"too long",3)).status());
  }
}
