import local.cecprobe.BridgeAuth;
import local.cecprobe.BridgeBootstrapWire;
import local.cecprobe.BridgeConfig;
import local.cecprobe.BridgeWire;
import android.net.*;
import android.system.*;
import android.view.*;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
public class BridgeHelper {
 static volatile boolean grabbed=false;
 static volatile long progress=SystemClock.uptimeMillis();
 static Map<Integer,Integer> keys=new HashMap<Integer,Integer>();
 static Object inputManager; static Method inject,ioctl; static Object grabValue;
 static String remotePath()throws Exception{
  Process p=new ProcessBuilder("/system/bin/getevent","-p").redirectErrorStream(true).start();
  final Process process=p;final java.util.concurrent.atomic.AtomicBoolean complete=new java.util.concurrent.atomic.AtomicBoolean(false);
  Thread timer=new Thread(new Runnable(){public void run(){try{Thread.sleep(2000);}catch(InterruptedException ignored){}if(!complete.get())process.destroy();}});timer.setDaemon(true);timer.start();
  BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()));String line,path=null,found=null;
  while((line=r.readLine())!=null){if(line.startsWith("add device")){path=line.substring(line.lastIndexOf(":")+1).trim();}if(line.contains(BridgeConfig.REMOTE_NAME))found=path;}
  p.waitFor();complete.set(true);return found;
 }
 static void loadKeys()throws Exception{
  BufferedReader r=new BufferedReader(new FileReader("/system/usr/keylayout/Generic.kl"));String line;
  while((line=r.readLine())!=null){String[] x=line.trim().split("\\s+");if(x.length>=3&&x[0].equals("key")){try{int k=KeyEvent.keyCodeFromString("KEYCODE_"+x[2]);if(k!=0)keys.put(Integer.parseInt(x[1]),k);}catch(Exception e){}}}r.close();
  keys.put(1,4);keys.put(28,23);keys.put(102,3);keys.put(139,82);keys.put(158,4);keys.put(172,3);
 }
 static final ConfirmHold confirm=new ConfirmHold();
 static int heldScanCode;
 static void repeatConfirm(long now)throws Exception{
  if(injected.isStopping())return;
  if(confirm.poll(now)){int flags=confirm.repeat==1?128:0;
   KeyEvent e=new KeyEvent(confirm.down,now,0,confirm.key,confirm.repeat,0,-1,heldScanCode,flags,0x101);
   if(!(Boolean)inject.invoke(inputManager,e,0))throw new IOException("Confirm repeat rejected");
  }
 }
 static final HeldKeyRelease injected=new HeldKeyRelease();
 static final HeldKeyRelease.Sender releaseSender=new HeldKeyRelease.Sender(){public void keyUp(int key,long downTime)throws Exception{
  if(!(Boolean)inject.invoke(inputManager,new KeyEvent(downTime,SystemClock.uptimeMillis(),1,key,0,0,-1,0,0,0x101),0))throw new IOException("Key release rejected");
 }};
 static void releaseInjected(){confirm.clear();injected.releaseAll(releaseSender);}
 static void close(FileDescriptor fd){try{if(fd!=null)Os.close(fd);}catch(Exception e){}}
 static byte[] bootstrapKey(String name,int appUid)throws IOException{
  if(android.os.Process.myUid()!=BridgeBootstrapWire.SHELL_UID||!BridgeBootstrapWire.validName(name)||appUid<10000)
   throw new IOException("Invalid private bootstrap endpoint");
  try(LocalSocket socket=new LocalSocket()){
   socket.connect(new LocalSocketAddress(name,LocalSocketAddress.Namespace.ABSTRACT),5000);
   socket.setSoTimeout(5000);
   return BridgeBootstrapWire.receive(socket.getInputStream(),socket.getOutputStream(),socket.getPeerCredentials().getUid(),appUid);
  }
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=3||!"--bootstrap".equals(args[0]))throw new IOException("Expected a private local bootstrap endpoint");
  if(android.os.Process.is64Bit())throw new IOException("This Alpha supports 32-bit input_event only");
  final byte[] bridgeKey=bootstrapKey(args[1],Integer.parseInt(args[2]));
  LocalServerSocket lock;
  try{lock=new LocalServerSocket("local.cecprobe.helperlock");}catch(Exception e){System.out.println("HELPER_ALREADY_RUNNING");return;}
  final long end=Long.MAX_VALUE;
  loadKeys();
  SourceWatcher.start();
  PowerWatcher.start();
  FineVolumeWorker.start();
  AudioRouteWatcher.start();
  Class<?> im=Class.forName("android.hardware.input.InputManager");inputManager=im.getMethod("getInstance").invoke(null);inject=im.getMethod("injectInputEvent",InputEvent.class,int.class);
  Class<?> ref=Class.forName("android.system.Int32Ref");grabValue=ref.getConstructor(int.class).newInstance(1);ioctl=Class.forName("android.system.Os").getMethod("ioctlInt",FileDescriptor.class,int.class,ref);
  new Thread(new Runnable(){public void run(){while(true){try{Thread.sleep(250);}catch(Exception e){}long now=SystemClock.uptimeMillis();if(now>end||(grabbed&&now-progress>3500)){System.out.println("WATCHDOG_EXIT_RELEASE");try{injected.releaseBeforeExit(releaseSender,250);}finally{System.exit(0);}}}}},"ReleaseWatchdog").start();
  String lastError="";
  while(SystemClock.uptimeMillis()<end){
   FileDescriptor fd=null;Socket socket=new Socket();
   try{
    progress=SystemClock.uptimeMillis();socket.connect(new InetSocketAddress("127.0.0.1",BridgeConfig.PORT),700);socket.setSoTimeout(700);socket.setTcpNoDelay(true);
    InputStream reader=socket.getInputStream();OutputStream out=socket.getOutputStream();
    BridgeAuth.connect(reader,out,bridgeKey);
    PrintWriter writer=new PrintWriter(new OutputStreamWriter(out,"US-ASCII"),true);
    String path=null,lastRouteReport="";long lastRouteReportAt=0;
    byte[] event=new byte[16];long lastCheck=0;boolean direct=false;Map<Integer,Long> downs=new HashMap<Integer,Long>();Map<Integer,Integer> repeats=new HashMap<Integer,Integer>();Map<Integer,Integer> volumeOwners=new HashMap<Integer,Integer>();
    while(SystemClock.uptimeMillis()<end){
     long now=SystemClock.uptimeMillis();progress=now;
     if(now-lastCheck>=100){String notice=FineVolumeWorker.takeNotice();if(notice!=null){writer.println("FINE_ERROR "+notice);if(!"OK".equals(BridgeWire.readLine(reader)))throw new IOException("Status delivery unavailable");}writer.println("STATE");String state=BridgeWire.readLine(reader);if(state==null)break;
      String[] stateParts=state.split(" ");state=stateParts[0];
      PowerWatcher.setEnabled(stateParts.length>=2?Integer.parseInt(stateParts[1]):0);
      int selectedDivisor=stateParts.length>=3?Integer.parseInt(stateParts[2]):0;
      if(selectedDivisor!=3&&selectedDivisor!=6)selectedDivisor=0;
      FineVolumeWorker.setMode(state.equals("ACTIVE")&&AudioRouteWatcher.isHeadphones()?selectedDivisor:0);
      String routeReport="ROUTE "+AudioRouteWatcher.routeKind()+" "+selectedDivisor;
      if(!routeReport.equals(lastRouteReport)||now-lastRouteReportAt>=1000){writer.println(routeReport);if(!"OK".equals(BridgeWire.readLine(reader)))throw new IOException("Route status unavailable");lastRouteReport=routeReport;lastRouteReportAt=now;}
      if(!state.equals("ACTIVE")&&!state.equals("DIRECT")){if(fd!=null){releaseInjected();close(fd);grabbed=false;fd=null;downs.clear();repeats.clear();volumeOwners.clear();System.out.println("RELEASE_INACTIVE");}Thread.sleep(100);lastCheck=now;continue;}
      direct=state.equals("DIRECT");
      if(fd==null){path=remotePath();if(path==null)throw new IOException("Remote not found");progress=SystemClock.uptimeMillis();fd=Os.open(path,OsConstants.O_RDONLY|OsConstants.O_NONBLOCK|OsConstants.O_CLOEXEC,0);ioctl.invoke(null,fd,0x40044590,grabValue);grabbed=true;System.out.println("GRABBED "+path);}
      lastCheck=now;
     }
     if(fd==null){Thread.sleep(10);continue;}
     int n;try{n=Os.read(fd,event,0,16);}catch(ErrnoException e){if(e.errno==OsConstants.EAGAIN){repeatConfirm(SystemClock.uptimeMillis());Thread.sleep(8);continue;}throw e;}
     if(n!=16)throw new IOException("Unexpected input event length "+n);
     int type=(event[8]&255)|((event[9]&255)<<8),code=(event[10]&255)|((event[11]&255)<<8),val=(event[12]&255)|((event[13]&255)<<8)|((event[14]&255)<<16)|(event[15]<<24);
     if(type!=1||val<0||val>2)continue;
     Integer key=keys.get(code);if(key==null){System.out.println("UNMAPPED_RELEASE "+code);break;}
     if(ConfirmHold.handles(key)){
      if(val==0)confirm.release(key);
      else {if(val==2||confirm.key==key)continue;confirm.press(key,now);heldScanCode=code;}
     }
     int action=val==0?1:0,repeat=val==2?(repeats.containsKey(code)?repeats.get(code)+1:1):0;repeats.put(code,repeat);
     if(val==1||!downs.containsKey(code))downs.put(code,now);long down=downs.get(code);
     // Keep each physical press on its original path through key-up. A route change
     // must not replay a partly applied fine press through CEC or strand a CEC down.
     int volumeOwner=0;
     if(key==24||key==25){
      if(!volumeOwners.containsKey(key))volumeOwners.put(key,direct?3:FineVolumeWorker.enabled()?1:2);
      volumeOwner=volumeOwners.get(key);
     }
     if(volumeOwner==1){FineVolumeWorker.key(key,val);}
     else if(volumeOwner==2){writer.println("KEY "+key+" "+action+" "+repeat);if(!"OK".equals(BridgeWire.readLine(reader)))throw new IOException("Volume delivery unavailable");System.out.println("CEC_VOLUME "+key+" "+action+" "+repeat);}
     else{if(key==26&&val==1){PowerWatcher.captureBeforeSleep();progress=SystemClock.uptimeMillis();}
      if(action==0){if(!injected.trackDown(key,down))continue;}
      else{HeldKeyRelease.Key held=injected.claimUp(key);if(held==null)continue;down=held.downTime;}
      KeyEvent k=new KeyEvent(down,now,action,key,repeat,0,-1,code,0,0x101);boolean ok=(Boolean)inject.invoke(inputManager,k,0);if(!ok)throw new IOException("Key injection rejected");}
     if(val==0){downs.remove(code);repeats.remove(code);volumeOwners.remove(key);}
    }
   }catch(Throwable e){String kind=e.getClass().getName();if(!kind.equals(lastError)){System.out.println("RELEASE: "+e.toString());lastError=kind;}}
   finally{FineVolumeWorker.setMode(0);releaseInjected();close(fd);grabbed=false;try{socket.close();}catch(Exception e){}}
   progress=SystemClock.uptimeMillis();Thread.sleep(1000);
  }
  lock.close();System.exit(0);
 }
}
