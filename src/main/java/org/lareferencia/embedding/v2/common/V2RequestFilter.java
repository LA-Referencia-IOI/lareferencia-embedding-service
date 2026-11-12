package org.lareferencia.embedding.v2.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.lareferencia.embedding.v2.config.DvpProperties;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class V2RequestFilter extends OncePerRequestFilter {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(V2RequestFilter.class);
  public static final String REQUEST_ID = "dvp.request_id";
  private final DvpProperties properties;
  private final ObjectMapper mapper;
  private final java.util.concurrent.Semaphore uploads;
  public V2RequestFilter(DvpProperties properties, ObjectMapper mapper) { this.properties=properties; this.mapper=mapper;this.uploads=new java.util.concurrent.Semaphore(properties.getMaxConcurrentUploads()); }
  @Override protected boolean shouldNotFilter(HttpServletRequest r) {
    String path=r.getRequestURI().substring(r.getContextPath().length());
    return !(path.equals("/api/v2") || path.startsWith("/api/v2/"));
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String id=UUID.randomUUID().toString();
    request.setAttribute(REQUEST_ID,id); response.setHeader("X-Request-Id",id);
    if (!properties.isEnabled() || properties.getRole()==DvpProperties.Role.WORKER) {
      error(response,404,"not_found","API v2 is disabled for this process",id); return;
    }
    String path=request.getRequestURI().substring(request.getContextPath().length());
    boolean upload=properties.isImportEnabled() && "POST".equals(request.getMethod()) && path.matches("/api/v2/collections/[^/]+/imports");
    long maxBytes=properties.getMaxRequestBytes();
    if(upload) maxBytes=properties.getMaxFileBytes()>Long.MAX_VALUE-maxBytes?Long.MAX_VALUE:properties.getMaxFileBytes()+maxBytes;
    final long bodyLimit=maxBytes;
    if (request.getContentLengthLong()>bodyLimit) {
      error(response,413,"request_too_large","Request byte limit exceeded",id); return;
    }
    if(upload && (request.getHeader("Idempotency-Key")==null || request.getHeader("Idempotency-Key").isBlank())) {
      error(response,400,"invalid_request","Idempotency-Key header is required",id);return;
    }
    if(upload && !uploads.tryAcquire()) {response.setHeader("Retry-After","1");error(response,429,"upload_busy","Upload capacity is exhausted",id);return;}
    var wrapped = new HttpServletRequestWrapper(request) {
      @Override public ServletInputStream getInputStream() throws IOException {
        ServletInputStream input=super.getInputStream();
        return new ServletInputStream() {
          private long count;
          private void add(long n) {
            if (n>0 && (count+=n)>bodyLimit) {
              throw new V2Exception(413,"request_too_large","Request byte limit exceeded");
            }
          }
          @Override public int read() throws IOException { int value=input.read(); add(value<0?0:1); return value; }
          @Override public int read(byte[] b,int off,int len) throws IOException {
            int n=input.read(b,off,len); add(n); return n;
          }
          @Override public boolean isFinished() { return input.isFinished(); }
          @Override public boolean isReady() { return input.isReady(); }
          @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
        };
      }
    };
    boolean completed=false;
    try {chain.doFilter(wrapped,response);completed=true;} finally {
      if(upload)uploads.release();
      log.info("v2 request_id={} status={}",id,completed?Integer.toString(response.getStatus()):"unhandled");
    }
  }
  private void error(HttpServletResponse response,int status,String code,String message,String id) throws IOException {
    response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
    mapper.writeValue(response.getOutputStream(),V2Error.of(status,code,message,id));
    log.info("v2 request_id={} status={}",id,status);
  }
}
