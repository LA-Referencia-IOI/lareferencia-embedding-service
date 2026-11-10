package org.lareferencia.embedding.v2.indexation;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class HalfvecPolicyTest {
  @Test void checksOverflowAndMetricSpecificUnderflow() {
    assertTrue(HalfvecPolicy.compatible(new float[]{65505f,1f},"cosine"));
    assertFalse(HalfvecPolicy.compatible(new float[]{65520f,1f},"cosine"));
    assertFalse(HalfvecPolicy.compatible(new float[]{1e-9f,0f},"cosine"));
    assertTrue(HalfvecPolicy.compatible(new float[]{1e-9f,0f},"l2"));
    assertTrue(HalfvecPolicy.compatible(new float[]{0.00000006f,0f},"cosine"));
    assertFalse(HalfvecPolicy.compatible(new float[]{Float.NaN},"l2"));
  }
}
