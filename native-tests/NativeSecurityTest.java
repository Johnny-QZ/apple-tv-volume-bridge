import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

/** Synthetic transport and route regressions. No socket is bound or connected. */
final class NativeSecurityTest {
 interface Operation {void run()throws Exception;}
 static void check(boolean result,String name){if(!result)throw new AssertionError(name);}
 static void rejects(Operation operation,String name)throws Exception {try{operation.run();throw new AssertionError(name);}catch(IOException expected){}}
 static byte[] integerList(int count)throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(0xdf);
  for(int i=0;i<count;i++){out.write(0x32);Opack.le(out,i,4);}out.write(3);return out.toByteArray();
 }
 static void parsers()throws Exception {
  Object refs=Opack.unpack(new byte[]{(byte)0xd4,0x71,42,0x71,42,0x41,120,(byte)0xa1});
  check(refs instanceof List&&"x".equals(((List<?>)refs).get(3)),"duplicate byte arrays preserve reference indices");
  rejects(new Operation(){public void run()throws Exception{Opack.unpack(integerList(80000));}},"quadratic scalar payload rejected by budget");
  rejects(new Operation(){public void run()throws Exception{byte[] b=new byte[Opack.MAX_NODES+3];b[0]=(byte)0xdf;Arrays.fill(b,1,b.length-1,(byte)1);b[b.length-1]=3;Opack.unpack(b);}},"boolean nodes also bounded");
  byte[] value=new byte[600];Arrays.fill(value,(byte)31);
  check(Arrays.equals(value,CompanionClient.readTlv(CompanionClient.tlv(5,value)).get(5)),"valid TLV fragments concatenate");
  rejects(new Operation(){public void run()throws Exception{byte[] b=new byte[(PairingTlv.MAX_FRAGMENTS+1)*3];for(int i=0;i<b.length;i+=3){b[i]=3;b[i+1]=1;}CompanionClient.readTlv(b);}},"tiny TLV fragment attack rejected");
  rejects(new Operation(){public void run()throws Exception{CompanionClient.readTlv(CompanionClient.tlv(5,new byte[PairingTlv.MAX_FIELD+1]));}},"TLV aggregate field bound");
  rejects(new Operation(){public void run()throws Exception{CompanionClient.readTlv(new byte[PairingTlv.MAX_MESSAGE+1]);}},"TLV overall bound");
  final CompanionClient unauth=(CompanionClient)HelperOfflineTest.allocate(CompanionClient.class);
  int len=PairingTlv.MAX_MESSAGE+1;unauth.in=new DataInputStream(new ByteArrayInputStream(new byte[]{6,(byte)(len>>>16),(byte)(len>>>8),(byte)len}));
  rejects(new Operation(){public void run()throws Exception{unauth.receive(6);}},"pre-auth Companion length rejected before body allocation/read");
 }
 static CompanionConfig config()throws Exception {
  Properties p=new Properties();p.setProperty("appleTvHost","synthetic.invalid");p.setProperty("companionPort","1");
  char[] hex=new char[64];Arrays.fill(hex,'0');p.setProperty("companionCredentials",new String(hex)+":"+new String(hex)+":01:02");
  p.setProperty("headphoneNames","Headphone A|Headphone B");
  Constructor<CompanionConfig> c=CompanionConfig.class.getDeclaredConstructor(Properties.class);c.setAccessible(true);return c.newInstance(p);
 }
 static AirPlayRouteProbe route(String name,String uid)throws Exception {
  AirPlayRouteProbe p=HelperOfflineTest.probe();
  p.consume(HelperOfflineTest.pb(20,HelperOfflineTest.pb(2,"TV",19,"tv-id",24,1,28,HelperOfflineTest.pb(1,uid,2,name,19,"device-"+uid)),22,HelperOfflineTest.pb(2,3)));
  return p;
 }
 static final class FakeVolume implements VolumeTransport {
  Runnable duringRead;int reads,writes;double written;
  public void close(){}
  public double getVolume(){if(reads++==0){if(duringRead!=null)duringRead.run();return 80;}return written;}
  public void setVolume(double v){writes++;written=v;}
 }
 static void routes()throws Exception {
  android.os.SystemClock.now=1000;FineVolumeWorker.setMode(0);AudioRouteWatcher.unknown();
  final CompanionConfig config=config();final AirPlayRouteProbe a=route("Headphone A","uid-a"),b=route("Headphone B","uid-b");
  AudioRouteWatcher.update(a,config,1000);FineVolumeWorker.setMode(3);FineVolumeWorker.key(24,1);
  AudioRouteWatcher.Snapshot baseline=AudioRouteWatcher.verifiedSnapshot();long generation=baseline.generation;
  AudioRouteWatcher.update(route("Headphone A","uid-a"),config,1000);
  check(AudioRouteWatcher.snapshot.generation==generation&&FineVolumeWorker.queue.size()==1,"same-route heartbeat preserves queued tap");
  final FineVolumeWorker.Press press=FineVolumeWorker.queue.remove();
  FakeVolume client=new FakeVolume();
  client.duringRead=new Runnable(){public void run(){AudioRouteWatcher.update(b,config,1000);}};
  double before=client.getVolume();FineVolumeWorker.Attempt attempt=new FineVolumeWorker.Attempt();
  check(!FineVolumeWorker.writeVolume(press,baseline,before,client,attempt)&&client.writes==0&&!attempt.sent,"80 percent A baseline cannot overwrite 5 percent B");
  long next=AudioRouteWatcher.snapshot.generation;AudioRouteWatcher.update(route("Headphone B","new-device-same-name"),config,1000);
  check(AudioRouteWatcher.snapshot.generation>next,"same name but new child UID invalidates route");
  FineVolumeWorker.key(24,0);FineVolumeWorker.key(24,1);check(!FineVolumeWorker.queue.isEmpty(),"fresh tap queued");
  AudioRouteWatcher.unknown();check(FineVolumeWorker.queue.isEmpty()&&!AudioRouteWatcher.isCurrent(baseline),"unknown route cancels pending work");
  AudioRouteWatcher.update(a,config,1000);FineVolumeWorker.key(24,0);FineVolumeWorker.key(24,1);
  android.os.SystemClock.now=7000;check(AudioRouteWatcher.verifiedSnapshot()==null&&FineVolumeWorker.queue.isEmpty(),"expiry cancels pending work");
  FineVolumeWorker.setMode(0);AudioRouteWatcher.unknown();android.os.SystemClock.now=1000;
 }
 static final class FakeSocket extends Socket {
  volatile boolean stopped;
  public synchronized void close(){stopped=true;notifyAll();}
  synchronized void awaitClose()throws IOException {while(!stopped)try{wait();}catch(InterruptedException e){throw new IOException(e);}throw new SocketException("synthetic closed socket");}
 }
 static HapSocket transport(FakeSocket socket,InputStream input,OutputStream output)throws Exception {
  HapSocket s=(HapSocket)HelperOfflineTest.allocate(HapSocket.class);HelperOfflineTest.field(s,"socket",socket);HelperOfflineTest.field(s,"raw",new DataInputStream(input));HelperOfflineTest.field(s,"out",output);s.buffered=new byte[0];return s;
 }
 static void deadlines()throws Exception {
  final FakeSocket blocked=new FakeSocket();final HapSocket writer=transport(blocked,new ByteArrayInputStream(new byte[0]),new OutputStream(){public void write(int b)throws IOException{blocked.awaitClose();}});
  long start=System.nanoTime();
  try(SocketDeadline deadline=new SocketDeadline(blocked,60)){rejects(new Operation(){public void run()throws Exception{writer.write(new byte[]{1});}},"blocked write interrupted");}
  check(blocked.stopped&&(System.nanoTime()-start)/1000000<1500,"deadline closes blocked writer");
  final FakeSocket drip=new FakeSocket();
  final HapSocket reader=transport(drip,new InputStream(){public int read()throws IOException{if(drip.stopped)throw new SocketException("closed");try{Thread.sleep(15);}catch(InterruptedException e){throw new IOException(e);}return 'x';}},new ByteArrayOutputStream());
  start=System.nanoTime();
  rejects(new Operation(){public void run()throws Exception{reader.http("POST","/pair-verify","HTTP/1.1","application/octet-stream",new byte[0],0);}},"slow-drip whole HTTP deadline");
  check(drip.stopped&&(System.nanoTime()-start)/1000000<6500,"byte progress does not extend whole operation deadline");
  FakeSocket complete=new FakeSocket();try(SocketDeadline deadline=new SocketDeadline(complete,30)){deadline.check();}Thread.sleep(60);
  check(!complete.stopped&&SocketDeadline.pendingForTests()==0,"completed deadlines canceled without closing reused sockets or retaining tasks");
 }
 static void run()throws Exception {parsers();routes();deadlines();System.out.println("PASS native security parser budgets, route-bound volume reads, whole-operation read/write deadlines");}
}
