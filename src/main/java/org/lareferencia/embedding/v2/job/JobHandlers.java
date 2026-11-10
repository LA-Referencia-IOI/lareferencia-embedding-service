package org.lareferencia.embedding.v2.job;

import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Validated registry of handlers actually implemented by this application. */
@Component
@ConditionalOnProperty(name="app.dvp.enabled",havingValue="true")
public class JobHandlers {
  private final Map<String,JobHandler> handlers;
  public JobHandlers(ObjectProvider<JobHandler> providers) {
    Map<String,JobHandler> found=new HashMap<>();
    providers.orderedStream().forEach(handler->{
      if(!Set.of("import","prepare","generate","maintenance").contains(handler.kind())
          || found.putIfAbsent(handler.kind(),handler)!=null)
        throw new IllegalStateException("Invalid or duplicate job handler kind");
    });
    handlers=Map.copyOf(found);
  }
  public Set<String> supportedKinds() { return handlers.keySet(); }
  public Optional<JobHandler> find(String kind) { return Optional.ofNullable(handlers.get(kind)); }
}
