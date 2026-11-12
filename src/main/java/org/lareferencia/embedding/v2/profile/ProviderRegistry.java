package org.lareferencia.embedding.v2.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runtime-only provider connections. Never log input, credentials, response bodies or endpoint URLs. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class ProviderRegistry {
  private record Connection(String type,URI endpoint,String secretRef,Duration timeout,int batchSize,
      Semaphore slots,HttpClient client) {}
  private final Map<String,Connection> providers;
  private final ThreadPoolExecutor executor;
  private final ObjectMapper mapper;
  public ProviderRegistry(ProviderProperties properties,ThreadPoolExecutor inferenceExecutor,ObjectMapper mapper) {
    this.executor=inferenceExecutor;this.mapper=mapper;
    var configured=new HashMap<String,Connection>();
    properties.getProviders().forEach((name,p)->{
      try {
        URI uri=URI.create(p.getEndpoint());
        if(!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}") ||
            !("OLLAMA".equals(p.getType()) || "OPENAI_COMPATIBLE".equals(p.getType())) ||
            !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null ||
            uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null ||
            p.getConcurrency()<1 || p.getBatchSize()<1 || p.getConnectTimeout()==null || p.getConnectTimeout().isZero() ||
            p.getConnectTimeout().isNegative() || p.getRequestTimeout()==null || p.getRequestTimeout().isZero() ||
            p.getRequestTimeout().isNegative() || (p.getSecretRef()!=null && !p.getSecretRef().matches("[A-Za-z_][A-Za-z0-9_]*")))
          throw new IllegalArgumentException();
        configured.put(name,new Connection(p.getType(),uri,p.getSecretRef(),p.getRequestTimeout(),p.getBatchSize(),
            new Semaphore(p.getConcurrency()),HttpClient.newBuilder().connectTimeout(p.getConnectTimeout()).build()));
      } catch(RuntimeException e) { throw new IllegalArgumentException("Invalid v2 provider configuration for reference: "+name); }
    });
    providers=Map.copyOf(configured);
  }
  public boolean contains(String reference) { return reference!=null && providers.containsKey(reference); }
  public int batchSize(ProfileResource profile) {
    Connection connection=providers.get(profile.providerRef());
    if(connection==null) throw new V2Exception(503,"provider_unavailable","Provider configuration unavailable");
    return connection.batchSize();
  }
  /** Sanitized transport classification retained for persisted retry scheduling. */
  public static final class ProviderFailure extends V2Exception {
    private final int upstreamStatus;private final String retryAfter;
    ProviderFailure(int status,String retryAfter) {
      super(503,"provider_unavailable","Provider request failed");this.upstreamStatus=status;this.retryAfter=retryAfter;
    }
    public int upstreamStatus() { return upstreamStatus; }
    public String retryAfter() { return retryAfter; }
  }

  /** Call only after finishing database transactions. Input is already prepared and bounded by the caller. */
  public List<float[]> embed(ProfileResource profile,List<String> inputs) {
    if(!"http".equals(profile.executionMode())) throw new V2Exception(409,"profile_not_executable","Profile has no inference capability");
    Connection c=providers.get(profile.providerRef());
    if(c==null) throw new V2Exception(503,"provider_unavailable","Provider configuration unavailable");
    if(inputs==null || inputs.isEmpty() || inputs.size()>c.batchSize() || inputs.stream().anyMatch(s->s==null || s.isBlank()))
      throw new V2Exception(422,"invalid_provider_input","Provider input batch is invalid or exceeds its limit");
    if(!c.slots().tryAcquire()) throw new V2Exception(503,"inference_busy","Provider capacity is exhausted");
    Future<List<float[]>> future;
    // State prevents a cancelled task that never starts from leaking its provider permit.
    var state=new AtomicInteger();
    try { future=executor.submit(()->{if(!state.compareAndSet(0,1)) throw new CancellationException();return call(c,profile,inputs);}); }
    catch(RejectedExecutionException e) { c.slots().release();throw new V2Exception(503,"inference_busy","Inference capacity is exhausted"); }
    try { return future.get(c.timeout().toMillis()+1000,TimeUnit.MILLISECONDS); }
    catch(InterruptedException e) { future.cancel(true);if(state.compareAndSet(0,2)) c.slots().release();Thread.currentThread().interrupt();throw new V2Exception(503,"provider_unavailable","Provider call interrupted"); }
    catch(TimeoutException e) { future.cancel(true);if(state.compareAndSet(0,2)) c.slots().release();throw new V2Exception(503,"provider_timeout","Provider request timed out"); }
    catch(ExecutionException e) { if(e.getCause() instanceof V2Exception v) throw v;throw new V2Exception(503,"provider_unavailable","Provider request failed"); }
  }
  private List<float[]> call(Connection c,ProfileResource profile,List<String> inputs) {
    try {
      var body=mapper.createObjectNode().put("model",profile.model());
      body.set("input",mapper.valueToTree(inputs));
      if(c.type().equals("OPENAI_COMPATIBLE")) body.put("dimensions",profile.dimensions());
      var builder=HttpRequest.newBuilder(c.endpoint()).timeout(c.timeout()).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
      if(c.secretRef()!=null) {
        String secret=System.getenv(c.secretRef());
        if(secret==null || secret.isBlank()) throw new V2Exception(503,"provider_unavailable","Provider credential unavailable");
        builder.header("Authorization","Bearer "+secret);
      }
      var response=c.client().send(builder.build(),HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()>=400 && response.statusCode()<500 && response.statusCode()!=429)
        throw new V2Exception(422,"provider_rejected","Provider rejected the request");
      if(response.statusCode()<200 || response.statusCode()>=300) throw new ProviderFailure(response.statusCode(),response.headers().firstValue("Retry-After").orElse(null));
      JsonNode root=mapper.readTree(response.body());
      float[][] ordered=new float[inputs.size()][];
      JsonNode data=root==null?null:root.get("data"), nativeVectors=root==null?null:root.get("embeddings");
      if(data!=null && data.isArray() && data.size()==inputs.size()) {
        for(JsonNode item:data) {
          JsonNode index=item.get("index");
          if(index==null || !index.isIntegralNumber() || !index.canConvertToInt() || index.intValue()<0 || index.intValue()>=ordered.length || ordered[index.intValue()]!=null)
            throw invalidResponse();
          ordered[index.intValue()]=vector(item.get("embedding"),profile.dimensions());
        }
      } else if(c.type().equals("OLLAMA") && nativeVectors!=null && nativeVectors.isArray() && nativeVectors.size()==inputs.size()) {
        for(int i=0;i<ordered.length;i++) ordered[i]=vector(nativeVectors.get(i),profile.dimensions());
      } else throw invalidResponse();
      return Arrays.asList(ordered);
    } catch(V2Exception e) { throw e; }
    catch(InterruptedException e) { Thread.currentThread().interrupt();throw new V2Exception(503,"provider_unavailable","Provider call interrupted"); }
    catch(java.net.http.HttpTimeoutException e) { throw new V2Exception(503,"provider_timeout","Provider request timed out"); }
    catch(Exception e) { throw new V2Exception(503,"provider_unavailable","Provider request failed"); }
    finally { c.slots().release(); }
  }
  private float[] vector(JsonNode node,int dimensions) {
    if(node==null || !node.isArray() || node.size()!=dimensions) throw invalidResponse();
    float[] values=new float[dimensions];
    for(int i=0;i<dimensions;i++) { JsonNode value=node.get(i);if(!value.isNumber() || !Float.isFinite(value.floatValue())) throw invalidResponse();values[i]=value.floatValue(); }
    return values;
  }
  private V2Exception invalidResponse() { return new V2Exception(502,"invalid_provider_response","Provider returned invalid embeddings"); }
}
