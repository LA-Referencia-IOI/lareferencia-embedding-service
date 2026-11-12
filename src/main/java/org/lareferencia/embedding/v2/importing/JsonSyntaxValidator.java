package org.lareferencia.embedding.v2.importing;

import java.util.ArrayDeque;

/** Streaming JSON syntax validation for values too large to retain in memory. */
final class JsonSyntaxValidator {
  private static final class Frame {final int type;int state;Frame(int type){this.type=type;}}
  private final ArrayDeque<Frame> stack=new ArrayDeque<>();
  private boolean started,done,string,key,escape;private int hex,utfRemaining,utfMin=0x80,utfMax=0xbf;
  private String literal;private int literalPos,number;
  void accept(int c) throws JsonDocumentReader.SyntaxException {
    if(string){stringByte(c);return;}
    if(number!=0) {
      if(numberByte(c))return;
      if(!numberComplete() || !delimiter(c))fail();number=0;completeValue();accept(c);return;
    }
    if(literal!=null) {
      if(literalPos<literal.length()) {if(c!=literal.charAt(literalPos++))fail();return;}
      if(!delimiter(c))fail();literal=null;completeValue();accept(c);return;
    }
    if(space(c))return;
    if(done)fail();
    if(stack.isEmpty()) {if(started)fail();begin(c);return;}
    Frame f=stack.peek();
    if(f.type=='{') {
      switch(f.state) {
        case 0 -> {if(c=='}'){close();return;}if(c!='"')fail();string=true;key=true;return;}
        case 1 -> {if(c!=':')fail();f.state=2;return;}
        case 2 -> {begin(c);return;}
        case 3 -> {if(c=='}'){close();return;}if(c!=',')fail();f.state=4;return;}
        case 4 -> {if(c!='"')fail();string=true;key=true;return;}
        default -> fail();
      }
    } else {
      switch(f.state) {
        case 0 -> {if(c==']'){close();return;}begin(c);return;}
        case 1 -> {if(c==']'){close();return;}if(c!=',')fail();f.state=2;return;}
        case 2 -> {begin(c);return;}
        default -> fail();
      }
    }
  }
  private void begin(int c) throws JsonDocumentReader.SyntaxException {
    if(stack.isEmpty())started=true;
    if(c=='{' || c=='['){if(stack.size()>=1000)fail();stack.push(new Frame(c));return;}
    if(c=='"'){string=true;key=false;return;}
    if(c=='t' || c=='f' || c=='n'){literal=c=='t'?"true":c=='f'?"false":"null";literalPos=1;return;}
    if(c=='-'){number=1;return;}if(c=='0'){number=2;return;}if(c>='1' && c<='9'){number=3;return;}fail();
  }
  private boolean numberByte(int c) throws JsonDocumentReader.SyntaxException {
    boolean digit=c>='0' && c<='9';
    switch(number) {
      case 1 -> {if(c=='0')number=2;else if(c>='1' && c<='9')number=3;else fail();return true;}
      case 2 -> {if(c=='.'){number=4;return true;}if(c=='e' || c=='E'){number=6;return true;}if(digit)fail();return false;}
      case 3 -> {if(digit)return true;if(c=='.'){number=4;return true;}if(c=='e' || c=='E'){number=6;return true;}return false;}
      case 4 -> {if(!digit)fail();number=5;return true;}
      case 5 -> {if(digit)return true;if(c=='e' || c=='E'){number=6;return true;}return false;}
      case 6 -> {if(c=='+' || c=='-'){number=7;return true;}if(!digit)fail();number=8;return true;}
      case 7 -> {if(!digit)fail();number=8;return true;}
      case 8 -> {return digit;}
      default -> {fail();return false;}
    }
  }
  private boolean numberComplete(){return number==2 || number==3 || number==5 || number==8;}
  private void stringByte(int c) throws JsonDocumentReader.SyntaxException {
    if(utfRemaining>0) {if(c<utfMin || c>utfMax)fail();utfRemaining--;utfMin=0x80;utfMax=0xbf;return;}
    if(hex>0){if(!((c>='0' && c<='9') || (c>='a' && c<='f') || (c>='A' && c<='F')))fail();hex--;return;}
    if(escape){escape=false;if(c=='u'){hex=4;return;}if(c=='"' || c=='\\' || c=='/' || c=='b' || c=='f' || c=='n' || c=='r' || c=='t')return;fail();}
    if(c=='\\'){escape=true;return;}
    if(c=='"'){string=false;if(key){key=false;stack.peek().state=1;}else completeValue();return;}
    if(c<0x20)fail();if(c<0x80)return;
    if(c>=0xc2 && c<=0xdf){utfRemaining=1;return;}
    if(c>=0xe0 && c<=0xef){utfRemaining=2;if(c==0xe0)utfMin=0xa0;if(c==0xed)utfMax=0x9f;return;}
    if(c>=0xf0 && c<=0xf4){utfRemaining=3;if(c==0xf0)utfMin=0x90;if(c==0xf4)utfMax=0x8f;return;}
    fail();
  }
  private void close() throws JsonDocumentReader.SyntaxException {stack.pop();completeValue();}
  private void completeValue() throws JsonDocumentReader.SyntaxException {
    if(stack.isEmpty()){done=true;return;}
    Frame parent=stack.peek();
    if(parent.type=='{') {if(parent.state!=2)fail();parent.state=3;}
    else {if(parent.state!=0 && parent.state!=2)fail();parent.state=1;}
  }
  void finish() throws JsonDocumentReader.SyntaxException {
    if(number!=0){if(!numberComplete())fail();number=0;completeValue();}
    if(literal!=null){if(literalPos!=literal.length())fail();literal=null;completeValue();}
    if(!done || string || escape || hex!=0 || utfRemaining!=0 || !stack.isEmpty())fail();
  }
  private boolean delimiter(int c){return space(c) || c==',' || c==']' || c=='}' || c==':';}
  private boolean space(int c){return c==' ' || c=='\t' || c=='\r' || c=='\n';}
  private void fail() throws JsonDocumentReader.SyntaxException {throw new JsonDocumentReader.SyntaxException();}
}
