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
}
