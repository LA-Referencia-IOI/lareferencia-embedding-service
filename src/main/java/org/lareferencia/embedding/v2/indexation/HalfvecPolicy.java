package org.lareferencia.embedding.v2.indexation;

/** Shared admission check for half precision ANN; stored values remain float32. */
public final class HalfvecPolicy {
  private HalfvecPolicy() { }
  public static boolean compatible(float[] values,String metric) {
    if(values==null || values.length==0 || values.length>4000 || !java.util.Set.of("cosine","l2","inner_product").contains(metric)) return false;
    boolean nonzero=false;
    for(float value:values) {
      if(!Float.isFinite(value)) return false;
      float converted=Float.float16ToFloat(Float.floatToFloat16(value));
      if(!Float.isFinite(converted)) return false;
      if(converted!=0f) nonzero=true;
    }
    return !metric.equals("cosine") || nonzero;
  }
}
