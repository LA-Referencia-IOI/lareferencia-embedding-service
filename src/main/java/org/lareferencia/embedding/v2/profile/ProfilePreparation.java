package org.lareferencia.embedding.v2.profile;

import java.text.Normalizer;
import java.util.Locale;
import org.lareferencia.embedding.v2.common.V2Exception;
import org.springframework.stereotype.Component;

@Component
public class ProfilePreparation {
  public String document(ProfileResource profile,String input,int maxCodePoints) { return prepare(profile,input,maxCodePoints,false); }
  public String query(ProfileResource profile,String input,int maxCodePoints) { return prepare(profile,input,maxCodePoints,true); }
  private String prepare(ProfileResource profile,String input,int maxCodePoints,boolean query) {
    if(input==null) throw new V2Exception(422,"invalid_input","Input is required");
    var preparation=profile.preparation();
    String normalized=switch(preparation.version()) {
      case "identity-v1" -> input;
      case "legacy-normalizer-v1" -> legacy(input);
      default -> throw new V2Exception(422,"invalid_preparation","Unknown preparation version");
    };
    String result=(query?preparation.queryPrefix():preparation.documentPrefix())+normalized;
    if(normalized.isEmpty() || normalized.codePoints().allMatch(c->Character.isWhitespace(c) || Character.isSpaceChar(c)) || result.codePointCount(0,result.length())>maxCodePoints)
      throw new V2Exception(422,"invalid_input","Prepared input is empty or exceeds its limit");
    return result;
  }
  // Frozen v1 recipe: future changes to the legacy API must not change a profile's identity.
  private String legacy(String input) {
    if(input.isBlank()) return "";
    String result=input.trim().replaceAll("-\\s*\\n\\s*","").toLowerCase(Locale.ROOT)
        .replaceAll("[\\n\\r]+"," ").replaceAll("\\s+"," ");
    return Normalizer.normalize(result,Normalizer.Form.NFC);
  }
}
