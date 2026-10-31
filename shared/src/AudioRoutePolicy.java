import java.text.Normalizer;
import java.util.Collection;

/** Explicit route allowlist and freshness guard; unknown or mixed routes use CEC. */
final class AudioRoutePolicy {
 static final long FRESH_MS = 6000;
 static String normalizeName(String name) {
  return name == null ? "" : Normalizer.normalize(name.trim(), Normalizer.Form.NFC);
 }
 static boolean exclusiveHeadphones(Collection<String> outputs, Collection<String> allowed) {
  if(outputs == null || outputs.size()!=1 || allowed == null || allowed.isEmpty())return false;
  for(String name:outputs)if(normalizeName(name).isEmpty() || !allowed.contains(normalizeName(name)))return false;
  return true;
 }
 static boolean useFine(boolean selectedHeadphones,int capability,long verifiedAt,long now){
  return selectedHeadphones&&(capability==2||capability==3)&&verifiedAt>0&&now>=verifiedAt&&now-verifiedAt<FRESH_MS;
 }
}
