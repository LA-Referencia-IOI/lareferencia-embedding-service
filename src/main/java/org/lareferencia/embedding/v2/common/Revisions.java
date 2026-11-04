package org.lareferencia.embedding.v2.common;

public final class Revisions {
  private Revisions() {}
  public static Long ifMatch(String header) {
    if (header==null) return null;
    if (!header.matches("\"[1-9][0-9]*\"")) throw V2Exception.badRequest("If-Match must be a quoted positive revision");
    try { return Long.parseLong(header.substring(1,header.length()-1)); }
    catch (NumberFormatException e) { throw V2Exception.badRequest("If-Match revision is out of range"); }
  }
  public static void requireMatch(Long expected, Long actual) {
    if (expected!=null && !expected.equals(actual)) throw new V2Exception(412,"revision_conflict","Document revision differs from If-Match");
  }
  public static String etag(long revision) { return "\""+revision+"\""; }
}
