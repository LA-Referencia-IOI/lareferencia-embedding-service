package org.lareferencia.embedding.v2.importing;

import jakarta.servlet.MultipartConfigElement;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.*;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name={"app.dvp.enabled","app.dvp.import-enabled"},havingValue="true")
public class ImportMultipartConfig implements WebMvcConfigurer {
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;
  public ImportMultipartConfig(com.fasterxml.jackson.databind.ObjectMapper mapper) {this.mapper=mapper;}
  @Bean MultipartConfigElement dvpMultipartConfigElement(DvpProperties properties) {
    var factory=new MultipartConfigFactory();factory.setFileSizeThreshold(DataSize.ofBytes(0));
    factory.setMaxFileSize(DataSize.ofBytes(properties.getMaxFileBytes()));
    long overhead=properties.getMaxRequestBytes(),file=properties.getMaxFileBytes();
    factory.setMaxRequestSize(DataSize.ofBytes(file>Long.MAX_VALUE-overhead?Long.MAX_VALUE:file+overhead));
    return factory.createMultipartConfig();
  }
  @Override public void extendHandlerExceptionResolvers(java.util.List<org.springframework.web.servlet.HandlerExceptionResolver> resolvers) {
    resolvers.addFirst((request,response,handler,error)->{
      String path=request.getRequestURI().substring(request.getContextPath().length());
      if(!(error instanceof org.springframework.web.multipart.MultipartException)
          || !path.matches("/api/v2/collections/[^/]+/imports"))return null;
      try {
        Object id=request.getAttribute(org.lareferencia.embedding.v2.common.V2RequestFilter.REQUEST_ID);
        boolean tooLarge=error instanceof org.springframework.web.multipart.MaxUploadSizeExceededException;
        int status=tooLarge?413:400;response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(),org.lareferencia.embedding.v2.common.V2Error.of(status,tooLarge?"file_too_large":"invalid_request",
            tooLarge?"Import file byte limit exceeded":"Invalid multipart request",id==null?java.util.UUID.randomUUID().toString():id.toString()));
        return new org.springframework.web.servlet.ModelAndView();
      } catch(java.io.IOException failed) {return null;}
    });
  }
}
