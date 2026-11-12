package org.lareferencia.embedding.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v2.common.*;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.springframework.mock.web.*;

class RequestFilterTest {
  @Test void disabledApiAndWorkerRoleRejectV2ButLeaveV1Alone() throws Exception {
    var config=new DvpProperties();
    var filter=new V2RequestFilter(config,new ObjectMapper().findAndRegisterModules());
    var called=new AtomicBoolean();
    var v1=new MockHttpServletRequest("GET","/api/v1/stores");
    filter.doFilter(v1,new MockHttpServletResponse(),(r,s)->called.set(true));assertTrue(called.get());
    for(boolean enabled:new boolean[] {false,true}) {
      config.setEnabled(enabled);config.setRole(DvpProperties.Role.WORKER);
      var response=new MockHttpServletResponse();called.set(false);
      filter.doFilter(new MockHttpServletRequest("GET","/api/v2/collections"),response,(r,s)->called.set(true));
      assertEquals(404,response.getStatus());assertFalse(called.get());
      assertTrue(new ObjectMapper().readTree(response.getContentAsString()).hasNonNull("request_id"));
    }
  }
  @Test void unknownLengthBodiesAreCountedWhileReading() throws Exception {
    var config=new DvpProperties();config.setEnabled(true);config.setMaxRequestBytes(10);
    var request=new MockHttpServletRequest("POST","/api/v2/collections") {
      @Override public long getContentLengthLong() { return -1; }
    };
    request.setContent(new byte[11]);
    var filter=new V2RequestFilter(config,new ObjectMapper().findAndRegisterModules());
    var error=assertThrows(V2Exception.class,()->filter.doFilter(request,new MockHttpServletResponse(),
        (r,s)->r.getInputStream().readAllBytes()));
    assertEquals(413,error.status());
  }
  @Test void importAdmissionIsBoundedBeforeMultipartParsing() throws Exception {
    var config=new DvpProperties();config.setEnabled(true);config.setImportEnabled(true);config.setMaxConcurrentUploads(1);
    var filter=new V2RequestFilter(config,new ObjectMapper().findAndRegisterModules());
    var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
    try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var pending=executor.submit(()->{var request=new MockHttpServletRequest("POST","/api/v2/collections/123/imports");request.addHeader("Idempotency-Key","one");
        filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{entered.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});return true;});
      try {
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
        var second=new MockHttpServletRequest("POST","/api/v2/collections/123/imports");second.addHeader("Idempotency-Key","two");
        var response=new MockHttpServletResponse();filter.doFilter(second,response,(r,s)->fail("Excess upload should not enter the chain"));
        assertEquals(429,response.getStatus());assertEquals("1",response.getHeader("Retry-After"));
      } finally {release.countDown();}
      assertTrue(pending.get(5,java.util.concurrent.TimeUnit.SECONDS));
    }
  }
  @Test void requestLogIncludesIdWithoutSensitiveInputs() throws Exception {
    var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(V2RequestFilter.class);
    var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
    try {
      var config=new DvpProperties();config.setEnabled(true);var filter=new V2RequestFilter(config,new ObjectMapper());
      var request=new MockHttpServletRequest("POST","/api/v2/collections/private-canary");request.setContent("secret-body-canary".getBytes(java.nio.charset.StandardCharsets.UTF_8));request.addHeader("Authorization","secret-header-canary");
      var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->{});
      String line=appender.list.getLast().getFormattedMessage();assertTrue(line.contains(response.getHeader("X-Request-Id")));
      assertFalse(line.contains("private-canary"));assertFalse(line.contains("secret-body-canary"));assertFalse(line.contains("secret-header-canary"));
    } finally {logger.detachAppender(appender);appender.stop();}
  }

}
