public class AudioRoutePolicyTest {
 public static void main(String[] args){
  if(!AudioRoutePolicy.useFine(true,3,10000,10001))throw new AssertionError("headphones");
  if(AudioRoutePolicy.useFine(false,3,10000,10001))throw new AssertionError("other output");
  if(AudioRoutePolicy.useFine(true,1,10000,10001))throw new AssertionError("relative-only output");
  if(AudioRoutePolicy.useFine(true,3,10000,16000))throw new AssertionError("stale route");
  if(AudioRoutePolicy.useFine(true,3,10000,9999))throw new AssertionError("invalid time");
  if(AudioRoutePolicy.useFine(true,3,0,1000))throw new AssertionError("no verified baseline");
  java.util.Set<String> allowed=new java.util.HashSet<String>(java.util.Arrays.asList("Configured headphones","Caf\u00e9"));
  if(!AudioRoutePolicy.exclusiveHeadphones(java.util.Arrays.asList("Configured headphones"),allowed))throw new AssertionError("configured output");
  if(!AudioRoutePolicy.exclusiveHeadphones(java.util.Arrays.asList(" Cafe\u0301 "),allowed))throw new AssertionError("Unicode normalization");
  if(AudioRoutePolicy.exclusiveHeadphones(java.util.Arrays.asList("Configured headphones","TV speakers"),allowed))throw new AssertionError("mixed output must fail closed");
  if(AudioRoutePolicy.exclusiveHeadphones(java.util.Arrays.asList("Configured headphones","Caf\u00e9"),allowed))throw new AssertionError("multiple allowed outputs are not exclusive");
  if(AudioRoutePolicy.exclusiveHeadphones(java.util.Arrays.asList("Unconfigured headphones"),allowed))throw new AssertionError("unconfigured name");
  if(AudioRoutePolicy.exclusiveHeadphones(java.util.Arrays.asList("configured headphones"),allowed))throw new AssertionError("name match must be case-sensitive");
  if(AudioRoutePolicy.exclusiveHeadphones(java.util.Arrays.asList("Configured headphones",""),allowed))throw new AssertionError("unnamed mixed output");
  if(AudioRoutePolicy.exclusiveHeadphones(java.util.Collections.<String>emptyList(),allowed))throw new AssertionError("unknown output");
  if(AudioRoutePolicy.exclusiveHeadphones(java.util.Arrays.asList("Configured headphones"),java.util.Collections.<String>emptySet()))throw new AssertionError("missing allowlist");
  System.out.println("PASS explicit names, mixed/unknown routes, Unicode normalization, capability and freshness");
 }
}
