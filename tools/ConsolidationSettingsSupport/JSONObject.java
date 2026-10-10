package org.json;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
/** Functional primitive JSON double: no mocked file IO or merge behavior. */
public class JSONObject {
    public static final Object NULL = new Object();
    private final Map<String,Object> values = new LinkedHashMap<>();
    public JSONObject() {}
    public JSONObject(String text) {
        Parser p = new Parser(text); p.ws(); p.take('{'); p.ws();
        if (p.peek() != '}') {
            while (true) {
                String key = p.string(); p.ws(); p.take(':'); p.ws();
                Object value;
                if (p.peek() == '"') value = p.string();
                else {
                    int start=p.i;
                    while(p.i<p.s.length() && ",} \r\n\t".indexOf(p.peek())<0) p.i++;
                    String token=p.s.substring(start,p.i);
                    if ("true".equals(token)) value=true;
                    else if ("false".equals(token)) value=false;
                    else if ("null".equals(token)) value=NULL;
                    else if(token.contains(".") || token.contains("e") || token.contains("E")) value=Double.valueOf(token);
                    else { long n=Long.parseLong(token); if(n>=Integer.MIN_VALUE && n<=Integer.MAX_VALUE) value=Integer.valueOf((int)n); else value=Long.valueOf(n); }
                }
                values.put(key,value); p.ws();
                if (p.peek()=='}') break;
                p.take(','); p.ws();
            }
        }
        p.take('}'); p.ws(); if(p.i!=p.s.length()) throw new IllegalArgumentException("trailing JSON");
    }
    private static class Parser {
        final String s; int i;
        Parser(String s) { this.s=s; }
        char peek() { return i<s.length()?s.charAt(i):'\0'; }
        void ws() { while(i<s.length() && Character.isWhitespace(peek())) i++; }
        void take(char c) { if(peek()!=c) throw new IllegalArgumentException("expected "+c); i++; }
        String string() {
            take('"'); StringBuilder b=new StringBuilder();
            while(peek()!='"') {
                if(i>=s.length()) throw new IllegalArgumentException("unterminated JSON");
                char c=s.charAt(i++);
                if(c=='\\') {
                    if(i>=s.length()) throw new IllegalArgumentException("escape");
                    c=s.charAt(i++);
                    if(c=='n') c='\n'; else if(c=='r') c='\r'; else if(c=='t') c='\t';
                }
                b.append(c);
            }
            take('"'); return b.toString();
        }
    }
    private static String quote(String s) { return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\""; }
    public JSONObject put(String k,Object v) { if(v==null) values.remove(k); else values.put(k,v); return this; }
    public int length() { return values.size(); }
    public boolean has(String k) { return values.containsKey(k); }
    public Object opt(String k) { return values.get(k); }
    public Object get(String k) { if(!has(k)) throw new IllegalArgumentException(k); return values.get(k); }
    public Object remove(String k) { return values.remove(k); }
    public Iterator<String> keys() { return values.keySet().iterator(); }
    public String optString(String k,String f) { Object v=opt(k); return v instanceof String?(String)v:f; }
    public boolean optBoolean(String k,boolean f) { Object v=opt(k); return v instanceof Boolean?(Boolean)v:f; }
    public int optInt(String k,int f) { Object v=opt(k); return v instanceof Number?((Number)v).intValue():f; }
    public long optLong(String k,long f) { Object v=opt(k); return v instanceof Number?((Number)v).longValue():f; }
    public double optDouble(String k,double f) { Object v=opt(k); return v instanceof Number?((Number)v).doubleValue():f; }
    public String toString() {
        StringBuilder b=new StringBuilder("{");
        values.forEach((k,v)->{ if(b.length()>1)b.append(','); b.append(quote(k)).append(':').append(v==NULL?"null":v instanceof String?quote((String)v):v.toString()); });
        return b.append('}').toString();
    }
}
