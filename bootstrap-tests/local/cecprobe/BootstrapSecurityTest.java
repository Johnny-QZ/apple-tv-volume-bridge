package local.cecprobe;
import android.content.Context;
import android.net.*;
import java.io.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;

public final class BootstrapSecurityTest {
 static final byte[] KEY=new byte[32];static{Arrays.fill(KEY,(byte)42);}
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
 interface Operation{void run()throws Exception;}
 static void reject(Operation action)throws Exception{try{action.run();throw new AssertionError("Unexpected success");}catch(IOException expected){}}
 static boolean contains(byte[] haystack,byte[] needle){outer:for(int i=0;i+needle.length<=haystack.length;i++){for(int j=0;j<needle.length;j++)if(haystack[i+j]!=needle[j])continue outer;return true;}return false;}
 public static void main(String[] args)throws Exception{
  if(args.length==1&&args[0].equals("--stop-script")){Method m=LocalAdb.class.getDeclaredMethod("stopKnownHelpers");m.setAccessible(true);System.out.print(m.invoke(null));return;}
  ByteArrayOutputStream rejectedOut=new ByteArrayOutputStream();
  reject(()->BridgeBootstrapWire.send(new ByteArrayInputStream(new byte[0]),rejectedOut,KEY,10002));
  check(rejectedOut.size()==0,"wrong UID received bootstrap bytes");
  reject(()->BridgeBootstrapWire.receive(new ByteArrayInputStream(new byte[0]),rejectedOut,10002,10001));
  check(rejectedOut.size()==0,"wrong server UID received an ACK");
  reject(()->BridgeBootstrapWire.receive(new ByteArrayInputStream(new byte[0]),rejectedOut,2000,2000));
  check(BridgeBootstrapWire.validName(BridgeConfig.BOOTSTRAP_PREFIX+"0123456789abcdef0123456789abcdef"),"valid random name");
  check(!BridgeBootstrapWire.validName(BridgeConfig.BOOTSTRAP_PREFIX+"invalid;command"),"unsafe socket name");
  BridgeBootstrap server=new BridgeBootstrap(KEY,1500);
  try(LocalSocket attacker=LocalSocket.connectForTest(server.name(),10002,10001)){check(attacker.getInputStream().read()==-1,"non-shell peer obtained secret");}
  try(LocalSocket helper=LocalSocket.connectForTest(server.name(),2000,10001)){
   byte[] received=BridgeBootstrapWire.receive(helper.getInputStream(),helper.getOutputStream(),helper.getPeerCredentials().getUid(),10001);
   check(Arrays.equals(received,KEY),"shell helper did not receive identity");
  }
  server.awaitDelivery();check(LocalServerSocket.count()==0,"one-shot listener retained");
  Field stored=BridgeBootstrap.class.getDeclaredField("key");stored.setAccessible(true);
  check(Arrays.equals((byte[])stored.get(server),new byte[32]),"bootstrap copy was not cleared");
  check(KEY[0]==42,"caller identity was cleared");
  long start=System.nanoTime();try(BridgeBootstrap timeout=new BridgeBootstrap(KEY,120)){reject(()->timeout.awaitDelivery());}
  check((System.nanoTime()-start)/1000000<1500&&LocalServerSocket.count()==0,"bootstrap deadline did not close listener");
  byte[] oldHeader={0x43,0x45,0x43,0x42,3,1};reject(()->BridgeAuth.accept(new ByteArrayInputStream(oldHeader),new ByteArrayOutputStream(),KEY));
  Path temp=Files.createTempDirectory("bootstrap-identity-test-");
  try{
   Context context=new Context(temp.toFile());Files.write(temp.resolve("bridge-v3.key"),KEY);
   byte[] fresh=BridgeSecrets.load(context);check(!Arrays.equals(fresh,KEY),"v3 identity reused");
   check(!Files.exists(temp.resolve("bridge-v3.key")),"legacy app identity retained");
   check(Arrays.equals(fresh,BridgeSecrets.load(context)),"v4 identity changed on reload");
   byte[] jar=new byte[64];Arrays.fill(jar,(byte)7);
   reject(()->LocalAdb.installAndStart(context,jar,KEY,null));
   check(FakeAdbSocket.challenges==0,"fake daemon unexpectedly authenticated");
   check(!contains(FakeAdbSocket.transcript.toByteArray(),KEY),"ADB received secret bytes");
   check(!new String(FakeAdbSocket.transcript.toByteArray(),"ISO-8859-1").contains("bridge-v4.key"),"shell key file was provisioned");
  }finally{try(java.util.stream.Stream<Path> files=Files.walk(temp)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}}
  System.out.println("PASS fake-ADB key isolation, mutual UID gates, one-shot delivery, expiry, v3 rejection and v4 identity rotation");
 }
}
