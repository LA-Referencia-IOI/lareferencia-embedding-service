package org.lareferencia.embedding.v2.importing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.Test;
import org.lareferencia.embedding.v2.common.V2Exception;

class ImportVectorValidatorTest {
  private final ObjectMapper mapper=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private final ImportVectorValidator validator=new ImportVectorValidator();
  @Test void acceptsFiniteFloat32AndPreservesValues() throws Exception {
    assertArrayEquals(new float[]{1.25f,-0.5f},validator.validate(mapper.readTree("[1.25,-0.5]"),2,"cosine","hnsw_vector"));
  }
  @Test void rejectsWrongDimensionsNonNumericOverflowAndCosineZero() throws Exception {
    for(String values:new String[]{"[1]","[1,\"2\"]","[1,1e100]","[1,1e-100]","[0,0]"})
      assertEquals("invalid_vector",assertThrows(V2Exception.class,()->validator.validate(mapper.readTree(values),2,"cosine","none")).code());
    assertArrayEquals(new float[]{0,0},validator.validate(mapper.readTree("[0,0]"),2,"l2","none"));
  }
  @Test void enforcesHalfvecOverflowAndMetricSpecificUnderflow() throws Exception {
    assertEquals("halfvec_not_representable",assertThrows(V2Exception.class,()->validator.validate(mapper.readTree("[70000,1]"),2,"l2","hnsw_halfvec")).code());
    assertEquals("halfvec_not_representable",assertThrows(V2Exception.class,()->validator.validate(mapper.readTree("[1e-9,0]"),2,"cosine","hnsw_halfvec")).code());
    assertArrayEquals(new float[]{1e-9f,0},validator.validate(mapper.readTree("[1e-9,0]"),2,"l2","hnsw_halfvec"));
  }
}
