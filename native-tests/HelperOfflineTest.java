import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
public class HelperOfflineTest {
 // Allocate transport holders without constructors so these tests cannot connect
 // to a device. All encrypted input is synthetic and uses a throwaway zero key.
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
 static Object allocate(Class<?> type)throws Exception{Class<?> c=Class.forName("sun.misc.Unsafe");Field f=c.getDeclaredField("theUnsafe");f.setAccessible(true);return c.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);}
 static void field(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
 static byte[] pb(Object... parts)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<parts.length;i+=2){int key=(Integer)parts[i];Object v=parts[i+1];if(v instanceof Number){out.write(AirPlayRouteProbe.var(key*8));out.write(AirPlayRouteProbe.var(((Number)v).longValue()));}else{byte[] b=v instanceof String?((String)v).getBytes("UTF-8"):(byte[])v;out.write(AirPlayRouteProbe.var(key*8+2));out.write(AirPlayRouteProbe.var(b.length));out.write(b);}}return out.toByteArray();}
 static AirPlayRouteProbe probe()throws Exception{AirPlayRouteProbe p=(AirPlayRouteProbe)allocate(AirPlayRouteProbe.class);p.names=new ArrayList<>();p.capability=-1;return p;}
 static HapSocket stream(InputStream in)throws Exception{HapSocket s=(HapSocket)allocate(HapSocket.class);field(s,"raw",new DataInputStream(in));s.buffered=new byte[0];s.readKey=new byte[32];return s;}
 public static void main(String[] args)throws Exception{
  Set<String> allow=new HashSet<>(Arrays.asList("Configured headphones"));
  AirPlayRouteProbe p=probe();
  byte[] headphone=pb(2,"Configured headphones",19,"headphone-id");
  p.consume(pb(20,pb(2,"TV",19,"tv-id",29,1,24,1,28,headphone),22,pb(1,1,2,3)));
  check(p.names.equals(Arrays.asList("Configured headphones"))&&p.capability==3,"proxy output model");
  check(AudioRoutePolicy.exclusiveHeadphones(p.names,allow),"configured exclusive route");
  long revision=p.routeRevision,stamp=p.routeReceivedNs;
  p.consume(pb(22,pb(1,1,2,3)));
  check(p.routeRevision==revision&&p.routeReceivedNs==stamp,"capability event must not renew identity");
  p.consume(pb(20,pb(2,"TV",19,"tv-id",29,1,24,0,28,headphone)));
  check(!AudioRoutePolicy.exclusiveHeadphones(p.names,allow),"audible host mixed output");
  p.consume(pb(20,pb(2,"TV",19,"tv-id",24,1,28,headphone,28,pb(2,"Other speaker"))));
  check(!AudioRoutePolicy.exclusiveHeadphones(p.names,allow),"mixed grouped output");
  p.consume(pb(20,pb(2,"TV",19,"tv-id",24,1,28,pb(2,"Configured headphones",28,pb(2,"Other speaker")))));
  check(p.names.isEmpty(),"nested topology fails closed");
  p.consume(pb(20,pb(2,"TV",19,"other-tv",24,1,28,headphone)));
  check(p.capability==-1,"identity change invalidates capability");
  Object packed=Opack.unpack(Opack.pack(Opack.map("name","test","integer",512,"list",Arrays.asList(true,2.5,"abc"))));
  check(packed instanceof Map&&Long.valueOf(512).equals(((Map<?,?>)packed).get("integer")),"OPACK roundtrip");
  try{Opack.unpack(new byte[]{0x61,(byte)255});throw new AssertionError("OPACK bounds");}catch(IOException expected){}
  byte[] key=new byte[32],header={1,0},payload=CompanionClient.crypt(true,key,HapSocket.nonce(0),header,new byte[]{42});
  HapSocket valid=stream(new ByteArrayInputStream(CompanionClient.join(header,payload)));
  check(valid.read()==42,"HAP decrypt");
  check(HapSocket.nonce(1)[4]==1&&CompanionClient.counter(1)[0]==1,"separate nonce conventions");
  HapSocket partial=stream(new InputStream(){int count;public int read()throws IOException{if(count++==0)return 1;throw new SocketTimeoutException();}});
  try{partial.read();throw new AssertionError("partial HAP accepted");}catch(SocketTimeoutException bad){throw new AssertionError("partial frame called idle");}catch(IOException expected){}
  HapSocket idle=stream(new InputStream(){public int read()throws IOException{throw new SocketTimeoutException();}});
  try{idle.read();throw new AssertionError("idle timeout missing");}catch(SocketTimeoutException expected){}
  FineVolumeWorker.setMode(3);FineVolumeWorker.key(24,1);android.os.SystemClock.now=1300;FineVolumeWorker.key(24,2);
  check(FineVolumeWorker.queue.size()==2,"tap+repeat queued");int epoch=FineVolumeWorker.repeatGeneration;
  FineVolumeWorker.key(24,0);check(FineVolumeWorker.queue.size()==1&&FineVolumeWorker.repeatGeneration>epoch,"release clears repeat but preserves tap");
  FineVolumeWorker.setMode(6);check(FineVolumeWorker.queue.isEmpty(),"mode change clears queue");
  for(int i=0;i<6;i++){FineVolumeWorker.key(25,1);FineVolumeWorker.key(25,0);}check(FineVolumeWorker.queue.size()==4,"bounded queue");
  FineVolumeWorker.queue.clear();FineVolumeWorker.key(24,1);FineVolumeWorker.cancelPending();android.os.SystemClock.now=2000;FineVolumeWorker.key(24,2);check(FineVolumeWorker.queue.isEmpty(),"failed hold blocked until release");FineVolumeWorker.key(24,0);FineVolumeWorker.key(24,1);check(FineVolumeWorker.queue.size()==1,"fresh tap after error");
  FineVolumeWorker.setMode(0);FineVolumeWorker.key(24,1);check(FineVolumeWorker.queue.isEmpty(),"disabled worker");
  NativeSecurityTest.run();
  System.out.println("PASS native route topology/freshness, OPACK bounds, HAP encryption/partial timeout, fine queue cancellation");
 }
}
