package local.cecprobe;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;

/** Small, serialized local ADB client. The TV must explicitly authorize its own app key. */
public final class LocalAdb {
 public interface Status { void update(String message); }
 private static final int CNXN=word("CNXN"),AUTH=word("AUTH"),OPEN=word("OPEN"),OKAY=word("OKAY"),WRTE=word("WRTE"),CLSE=word("CLSE");
 private LocalAdb(){}
 private static void status(Status s,String text){if(s!=null)s.update(text);}

 public static synchronized String shell(Context context,String command,Status status)throws Exception{
  KeyPair key=key(context);
  try(Connection c=new Connection(key,status)){return runShell(c,command);}
 }
 public static synchronized String shell(Context context,String command)throws Exception{return shell(context,command,null);}

 public static synchronized String installAndStart(Context context,byte[] jar,byte[] bridgeKey,Status status)throws Exception{
  if(jar==null||jar.length==0||jar.length>4*1024*1024)throw new IOException("辅助程序文件无效");
  if(bridgeKey==null||bridgeKey.length!=32)throw new IOException("本机认证密钥无效");
  KeyPair key=key(context);
  status(status,"正在连接电视本机调试接口…");
  try(Connection c=new Connection(key,status)){
   push(c,BridgeConfig.HELPER_PATH+".tmp",jar);
  }
  // Separate transports avoid a pending CLSE acknowledgement from the first
  // sync channel being mistaken for the next channel's OPEN response.
  try(Connection c=new Connection(key,status)){
   push(c,BridgeConfig.KEY_PATH+".tmp",bridgeKey);
  }
  // SYNC creates both temporary files with mode 0600. Explicitly set permissions
  // before an atomic rename; the secret is never put in shell argv or logs.
  String commit="chmod 600 "+BridgeConfig.KEY_PATH+".tmp "+BridgeConfig.HELPER_PATH+".tmp"
   +" && mv -f "+BridgeConfig.KEY_PATH+".tmp "+BridgeConfig.KEY_PATH
   +" && mv -f "+BridgeConfig.HELPER_PATH+".tmp "+BridgeConfig.HELPER_PATH
   +" && echo CEC_BRIDGE_FILES_READY";
  try(Connection c=new Connection(key,status)){
   if(!runShell(c,commit).contains("CEC_BRIDGE_FILES_READY"))throw new IOException("辅助程序文件安装失败");
  }
  status(status,"正在启动音量辅助程序…");
  // Validate the complete argv before stopping our v3 helper or the v0.2 prototype.
  String command="for task_pid in $(pidof app_process app_process32 app_process64 BridgeHelper); do task_proc=/proc/$task_pid; "
   +"task_cmd=$(tr '\\000' '\\n' < \"$task_proc/cmdline\" 2>/dev/null) || continue; "
   +"case \"$task_cmd\" in "
   +"'app_process\n/system/bin\nBridgeHelper'|'app_process\n/system/bin\nBridgeHelper\n180'|'BridgeHelper'|"
   +"'app_process\n/system/bin\nBridgeHelper\n"+BridgeConfig.KEY_PATH+"'|'BridgeHelper\n"+BridgeConfig.KEY_PATH+"') "
   +"kill \"${task_proc##*/}\" 2>/dev/null ;; esac; done; "
   +"sleep 1; umask 077; CLASSPATH="+BridgeConfig.HELPER_PATH+" nohup app_process /system/bin BridgeHelper "+BridgeConfig.KEY_PATH
   +" > "+BridgeConfig.LOG_PATH+" 2>&1 < /dev/null & sleep 1; "
   +"echo CEC_BRIDGE_START_REQUESTED";
  String result;
  try(Connection c=new Connection(key,status)){result=runShell(c,command);}
  if(!result.contains("CEC_BRIDGE_START_REQUESTED"))throw new IOException("未收到辅助程序启动确认");
  status(status,"辅助程序已启动，正在确认连接…");return result;
 }
 private static void push(Connection c,String remote,byte[] data)throws Exception{
  c.deadline(15000);Channel ch=c.open("sync:");
  try{
   ch.write(sync("SEND",(remote+",33152").getBytes("UTF-8")));
   for(int p=0;p<data.length;p+=32768){int n=Math.min(32768,data.length-p);ch.write(sync("DATA",Arrays.copyOfRange(data,p,p+n)));}
   byte[] done=new byte[8];put(done,0,word("DONE"));put(done,4,(int)(System.currentTimeMillis()/1000));ch.write(done);
   byte[] answer=ch.read(8);int id=get(answer,0),len=get(answer,4);
   if(id==word("FAIL")){if(len<0||len>65536)throw new IOException("ADB 文件传输失败");throw new IOException("ADB 文件传输失败："+new String(ch.read(len),"UTF-8"));}
   if(id!=OKAY)throw new IOException("ADB 文件传输未确认");
  }finally{ch.close();}
 }
 private static String runShell(Connection c,String command)throws Exception{
  c.deadline(15000);Channel ch=c.open("shell:"+command);ByteArrayOutputStream out=new ByteArrayOutputStream();
  try{for(;;){byte[] chunk=ch.readChunk();if(chunk==null)break;if(out.size()+chunk.length>1024*1024)throw new IOException("ADB 命令输出过大");out.write(chunk);}}
  finally{ch.close();}
  return new String(out.toByteArray(),"UTF-8");
 }
 private static byte[] sync(String id,byte[] data){byte[] out=new byte[8+data.length];put(out,0,word(id));put(out,4,data.length);System.arraycopy(data,0,out,8,data.length);return out;}

 private static KeyPair key(Context context)throws Exception{
  File dir=context.getNoBackupFilesDir();if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建本机调试密钥目录");
  AtomicFile file=new AtomicFile(new File(dir,"local_adb_rsa.pk8"));PrivateKey priv;
  if(file.getBaseFile().exists()||new File(file.getBaseFile()+".bak").exists()){
   byte[] bytes=file.readFully();priv=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
  }else{
   KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(new RSAKeyGenParameterSpec(2048,RSAKeyGenParameterSpec.F4),new SecureRandom());priv=generator.generateKeyPair().getPrivate();
   FileOutputStream out=null;try{out=file.startWrite();out.write(priv.getEncoded());file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
  }
  File pubFile=new File(dir,"local_adb_rsa.pub");
  byte[] savedPublic=null;
  if(pubFile.exists()){
   try(FileInputStream in=new FileInputStream(pubFile)){
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[1024];int n;
    while((n=in.read(block))!=-1){if(out.size()+n>4096)throw new IOException("本机调试公钥文件过大");out.write(block,0,n);}savedPublic=out.toByteArray();
   }
  }
  PublicKey pub=restorePublic(priv,savedPublic);
  if(!pubFile.exists()){try(FileOutputStream out=new FileOutputStream(pubFile)){out.write(publicPayload(pub));}}
  return new KeyPair(pub,priv);
 }
 /** Android providers may reload PKCS8 as a non-CRT RSAPrivateKey. Keep its identity. */
 private static PublicKey restorePublic(PrivateKey privateKey,byte[] savedPublic)throws Exception{
  if(!(privateKey instanceof RSAPrivateKey))throw new GeneralSecurityException("本机调试密钥格式无效");
  RSAPrivateKey rsa=(RSAPrivateKey)privateKey;BigInteger exponent;
  if(privateKey instanceof RSAPrivateCrtKey){exponent=((RSAPrivateCrtKey)privateKey).getPublicExponent();}
  else{
   if(savedPublic==null)throw new GeneralSecurityException("本机调试公钥文件缺失；已保留原私钥");
   String text=new String(savedPublic,"UTF-8");int space=text.indexOf(' ');
   if(space<=0)throw new GeneralSecurityException("本机调试公钥格式无效");
   byte[] raw=Base64.decode(text.substring(0,space),Base64.DEFAULT);
   if(raw.length!=524||get(raw,0)!=64)throw new GeneralSecurityException("本机调试公钥长度无效");
   byte[] modulus=new byte[256];for(int i=0;i<256;i++)modulus[255-i]=raw[8+i];
   if(!new BigInteger(1,modulus).equals(rsa.getModulus()))throw new GeneralSecurityException("本机调试公私钥不匹配；已保留原密钥");
   exponent=BigInteger.valueOf(get(raw,520)&0xffffffffL);
   if(!exponent.equals(BigInteger.valueOf(3))&&!exponent.equals(RSAKeyGenParameterSpec.F4))throw new GeneralSecurityException("本机调试公钥指数无效");
  }
  return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(),exponent));
 }
 private static byte[] publicPayload(PublicKey publicKey)throws Exception{
  RSAPublicKey rsa=(RSAPublicKey)publicKey;BigInteger n=rsa.getModulus(),r32=BigInteger.ONE.shiftLeft(32);
  byte[] raw=new byte[524];put(raw,0,64);put(raw,4,r32.subtract(n.mod(r32).modInverse(r32)).intValue());
  littleBig(n,raw,8,256);littleBig(BigInteger.ONE.shiftLeft(4096).mod(n),raw,264,256);put(raw,520,rsa.getPublicExponent().intValue());
  return (Base64.encodeToString(raw,Base64.NO_WRAP)+" cecbridge@localhost\0").getBytes("UTF-8");
 }
 private static byte[] signature(PrivateKey key,byte[] token)throws Exception{
  if(token.length!=20)throw new IOException("ADB 授权挑战格式无效");
  byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
  byte[] digestInfo=new byte[35];System.arraycopy(prefix,0,digestInfo,0,15);System.arraycopy(token,0,digestInfo,15,20);
  Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.ENCRYPT_MODE,key);return rsa.doFinal(digestInfo);
 }
 private static void littleBig(BigInteger value,byte[] dst,int start,int size){byte[] be=value.toByteArray();for(int i=0;i<size;i++){int j=be.length-1-i;dst[start+i]=j>=0?be[j]:0;}}
 private static int word(String s){return s.charAt(0)|(s.charAt(1)<<8)|(s.charAt(2)<<16)|(s.charAt(3)<<24);}
 private static int get(byte[] x,int p){return (x[p]&255)|((x[p+1]&255)<<8)|((x[p+2]&255)<<16)|(x[p+3]<<24);}
 private static void put(byte[] x,int p,int n){for(int i=0;i<4;i++)x[p+i]=(byte)(n>>>(8*i));}
 private static final class Packet{int cmd,a,b;byte[] data;Packet(int c,int a,int b,byte[] d){cmd=c;this.a=a;this.b=b;data=d;}}
 private static final class Connection implements Closeable{
  Socket socket=new Socket();InputStream in;OutputStream out;long end;int next=1,max=4096;
  Connection(KeyPair key,Status status)throws Exception{
   try{
    socket.connect(new InetSocketAddress("127.0.0.1",5555),2500);socket.setTcpNoDelay(true);in=socket.getInputStream();out=socket.getOutputStream();deadline(30000);
    send(CNXN,0x01000000,4096,"host::\0".getBytes("UTF-8"));boolean signed=false,published=false;
    for(;;){Packet p=receive();if(p.cmd==CNXN){max=Math.max(256,Math.min(4096,p.b));return;}
     if(p.cmd!=AUTH||p.a!=1)throw new IOException("本机 ADB 握手异常");
     if(!signed){send(AUTH,2,0,signature(key.getPrivate(),p.data));signed=true;}
     else if(!published){status(status,"请在电视弹窗中允许“USB 调试”，建议勾选始终允许此计算机");send(AUTH,3,0,publicPayload(key.getPublic()));published=true;}
     else throw new IOException("调试授权未获允许，请允许电视弹窗后重试");
    }
   }catch(Exception e){close();throw e;}
  }
  void deadline(long ms){end=System.nanoTime()+ms*1000000L;}
  int remaining()throws SocketTimeoutException{long n=(end-System.nanoTime())/1000000L;if(n<=0)throw new SocketTimeoutException("本机调试连接超时，请检查电视授权");return (int)Math.max(1,Math.min(30000,n));}
  byte[] exact(int n)throws IOException{byte[] b=new byte[n];int p=0;while(p<n){socket.setSoTimeout(remaining());int r=in.read(b,p,n-p);if(r<0)throw new EOFException("ADB 连接已关闭");p+=r;}return b;}
  Packet receive()throws IOException{
   byte[] h=exact(24);int cmd=get(h,0),len=get(h,12),sum=get(h,16);if(get(h,20)!=(cmd^-1)||len<0||len>1024*1024)throw new IOException("ADB 消息头无效");byte[] b=exact(len);int actual=0;for(byte v:b)actual+=v&255;if(sum!=0&&sum!=actual)throw new IOException("ADB 消息校验失败");return new Packet(cmd,get(h,4),get(h,8),b);
  }
  void send(int cmd,int a,int b,byte[] data)throws IOException{remaining();byte[] h=new byte[24];int sum=0;for(byte v:data)sum+=v&255;put(h,0,cmd);put(h,4,a);put(h,8,b);put(h,12,data.length);put(h,16,sum);put(h,20,cmd^-1);out.write(h);out.write(data);out.flush();}
  Channel open(String destination)throws Exception{
   int id=next++;send(OPEN,id,0,(destination+"\0").getBytes("UTF-8"));
   for(;;){Packet p=receive();if(p.cmd==OKAY&&p.b==id)return new Channel(this,id,p.a);if(p.cmd==CLSE&&p.b==id)throw new IOException("ADB 服务不可用："+destination.split(":",2)[0]);throw new IOException("ADB 服务握手异常");}
  }
  public void close(){try{socket.close();}catch(Exception ignored){}}
 }
 private static final class Channel implements Closeable{
  Connection c;int local,remote;boolean closed=false;ArrayDeque<byte[]> queue=new ArrayDeque<byte[]>();byte[] current;int at;
  Channel(Connection c,int l,int r){this.c=c;local=l;remote=r;}
  void accept(Packet p)throws IOException{
   if(p.b!=local||p.a!=remote)throw new IOException("ADB 通道编号错误");
   if(p.cmd==WRTE){queue.add(p.data);c.send(OKAY,local,remote,new byte[0]);}
   else if(p.cmd==CLSE){closed=true;c.send(CLSE,local,remote,new byte[0]);}
   else if(p.cmd!=OKAY)throw new IOException("ADB 通道消息无效");
  }
  void write(byte[] b)throws IOException{
   for(int p=0;p<b.length;p+=c.max){if(closed)throw new EOFException("ADB 通道已关闭");int n=Math.min(c.max,b.length-p);c.send(WRTE,local,remote,Arrays.copyOfRange(b,p,p+n));
    for(;;){Packet a=c.receive();accept(a);if(closed)throw new EOFException("ADB 文件传输被中断");if(a.cmd==OKAY)break;}
   }
  }
  byte[] readChunk()throws IOException{
   if(current!=null&&at<current.length){byte[] rest=Arrays.copyOfRange(current,at,current.length);current=null;at=0;return rest;}
   while(queue.isEmpty()&&!closed)accept(c.receive());return queue.isEmpty()?null:queue.remove();
  }
  byte[] read(int n)throws IOException{byte[] answer=new byte[n];int p=0;while(p<n){if(current==null||at==current.length){current=readChunk();at=0;if(current==null)throw new EOFException("ADB 响应不完整");}int take=Math.min(n-p,current.length-at);System.arraycopy(current,at,answer,p,take);at+=take;p+=take;}return answer;}
  public void close(){if(!closed){closed=true;try{c.send(CLSE,local,remote,new byte[0]);}catch(Exception ignored){}}}
 }
}
