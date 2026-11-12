package org.lareferencia.embedding.v2.importing;

import com.fasterxml.jackson.databind.JsonNode;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.lareferencia.embedding.v2.indexation.HalfvecPolicy;
import org.springframework.stereotype.Component;

/** Validates externally supplied numeric components before any record is committed. */
@Component
public class ImportVectorValidator {
  public float[] validate(JsonNode values,int dimensions,String metric,String strategy) {
    if(dimensions<1 || dimensions>16000 || metric==null || strategy==null || values==null || !values.isArray() || values.size()!=dimensions)
      throw invalid();
    if(!java.util.Set.of("cosine","l2","inner_product").contains(metric)
        || !java.util.Set.of("hnsw_vector","hnsw_halfvec","none").contains(strategy)) throw invalid();
    float[] result=new float[dimensions];boolean nonzero=false;
    for(int i=0;i<dimensions;i++) {
      JsonNode node=values.get(i);if(node==null || !node.isNumber()) throw invalid();
      float value=node.floatValue();if(!Float.isFinite(value)) throw invalid();
      if(value==0f && node.decimalValue().signum()!=0) throw invalid();
      result[i]=value;if(value!=0f)nonzero=true;
    }
    if("cosine".equals(metric) && !nonzero) throw invalid();
    if("hnsw_halfvec".equals(strategy) && !HalfvecPolicy.compatible(result,metric))
      throw new V2Exception(422,"halfvec_not_representable","Vector cannot be represented by halfvec");
    return result;
  }
  private V2Exception invalid() {return new V2Exception(422,"invalid_vector","Vector contains invalid dimensions or components");}
}
