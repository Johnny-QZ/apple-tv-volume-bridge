package local.cecprobe;
import android.net.LocalServerSocket;
import java.net.*;import java.io.*;import java.util.*;
/** Forged ADB daemon implemented entirely in memory; it never requests RSA authentication. */
public final class FakeAdbSocket extends Socket {
 static final ByteArrayOutputStream transcript=new ByteArrayOutputStream();
 static int challenges;
 final ArrayDeque<Integer> incoming=new ArrayDeque<Integer>();
 final ByteArrayOutputStream pending=new ByteArrayOutputStream();int local;
 static int word(String s){return s.charAt(0)|s.charAt(1)<<8|s.charAt(2)<<16|s.charAt(3)<<24;}
 static int get(byte[] b,int i){return(b[i]&255)|(b[i+1]&255)<<8|(b[i+2]&255)<<16|(b[i+3]&255)<<24;}
 static void put(byte[] b,int i,int v){for(int n=0;n<4;n++)b[i+n]=(byte)(v>>>(n*8));}
 void response(String cmd,int a,int b,byte[] data){byte[] h=new byte[24];int c=word(cmd),sum=0;for(byte x:data)sum+=x&255;put(h,0,c);put(h,4,a);put(h,8,b);put(h,12,data.length);put(h,16,sum);put(h,20,c^-1);for(byte x:h)incoming.add(x&255);for(byte x:data)incoming.add(x&255);}
 public void connect(SocketAddress address,int timeout){}public void setTcpNoDelay(boolean b){}public void setSoTimeout(int t){}public void close(){}
 public InputStream getInputStream(){return new InputStream(){public int read()throws IOException{if(incoming.isEmpty())throw new EOFException("No fake response pending");return incoming.remove();}};}
 public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){pending.write(b);}public void flush()throws IOException{
  byte[] p=pending.toByteArray();pending.reset();transcript.write(p);if(p.length<24)throw new IOException("Packet");int c=get(p,0);byte[] d=Arrays.copyOfRange(p,24,p.length);
  if(c==word("CNXN")){response("CNXN",0x1000000,4096,"device::\0".getBytes("UTF-8"));}
  else if(c==word("OPEN")){
   local=get(p,4);response("OKAY",1,local,new byte[0]);String service=new String(d,"UTF-8");
   if(service.startsWith("shell:")){
    boolean launch=service.contains("CEC_BRIDGE_START_REQUESTED");
    response("WRTE",1,local,(launch?"CEC_BRIDGE_START_REQUESTED\n":"CEC_BRIDGE_FILES_READY\n").getBytes("UTF-8"));response("CLSE",1,local,new byte[0]);
    if(launch){Thread expiry=new Thread(new Runnable(){public void run(){try{Thread.sleep(50);}catch(InterruptedException ignored){}LocalServerSocket.expireAll();}},"FakeBootstrapDeadline");expiry.setDaemon(true);expiry.start();}
   }
  }
  else if(c==word("WRTE")){response("OKAY",1,local,new byte[0]);if(d.length>=8&&get(d,0)==word("DONE")){byte[] ok=new byte[8];put(ok,0,word("OKAY"));response("WRTE",1,local,ok);}}
  else if(c==word("AUTH")){challenges++;throw new IOException("Unexpected AUTH");}
 }};}
}
