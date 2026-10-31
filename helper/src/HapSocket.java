import java.io.*;
import java.net.*;
import java.util.*;
/** Bounded HAP encrypted byte stream. Separate from Companion's framing. */
final class HapSocket implements Closeable {
 final Socket socket=new Socket();final DataInputStream raw;final OutputStream out;
 byte[] writeKey,readKey;long sent,received;byte[] buffered=new byte[0];int pos;
 HapSocket(String host,int port)throws Exception{try{socket.connect(new InetSocketAddress(host,port),3000);socket.setSoTimeout(4000);raw=new DataInputStream(socket.getInputStream());out=socket.getOutputStream();}catch(Exception error){try{socket.close();}catch(Exception ignored){}throw error;}}
 void enable(byte[] shared,String salt,String wi,String ri){writeKey=CompanionClient.hkdf(shared,salt,wi);readKey=CompanionClient.hkdf(shared,salt,ri);}
 static byte[] nonce(long counter){byte[] n=new byte[12];for(int i=0;i<8;i++)n[i+4]=(byte)(counter>>>(8*i));return n;}
 synchronized void write(byte[] bytes)throws Exception{
  try(SocketDeadline deadline=new SocketDeadline(socket,4000)){
  if(writeKey==null){out.write(bytes);out.flush();deadline.check();return;}
  for(int p=0;p<bytes.length;p+=1024){int size=Math.min(1024,bytes.length-p);byte[] header={(byte)size,(byte)(size>>>8)};
   out.write(header);out.write(CompanionClient.crypt(true,writeKey,nonce(sent++),header,Arrays.copyOfRange(bytes,p,p+size)));}out.flush();deadline.check();
  }
 }
 int read()throws Exception{
  if(readKey==null)return raw.readUnsignedByte();
  if(pos==buffered.length){int a=raw.readUnsignedByte();
   // Only timeout before the first byte is idle. A partial frame must reconnect.
   try(SocketDeadline deadline=new SocketDeadline(socket,4000)){int b=raw.readUnsignedByte(),len=a|(b<<8);if(len<1||len>65535)throw new IOException("HAP frame bound");byte[] enc=new byte[len+16];raw.readFully(enc);buffered=CompanionClient.crypt(false,readKey,nonce(received++),new byte[]{(byte)a,(byte)b},enc);pos=0;deadline.check();}
   catch(SocketTimeoutException partial){throw new IOException("Incomplete HAP frame",partial);}
  }
  return buffered[pos++]&255;
 }
 byte[] readBytes(int n)throws Exception{if(n<0||n>1048576)throw new IOException("Read bound");byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)read();return b;}
 String line()throws Exception{return line(read());}
 String line(int first)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();int c=first;while(b.size()<16384){if(c==10)return new String(b.toByteArray(),"UTF-8").trim();b.write(c);c=read();}throw new IOException("Header bound");}
 byte[] http(String method,String uri,String protocol,String type,byte[] body,int seq)throws Exception{
  try(SocketDeadline deadline=new SocketDeadline(socket,5000)){
  String h=method+" "+uri+" "+protocol+"\r\n"+(uri.equals("/pair-verify")?"X-Apple-HKP: 3\r\nConnection: keep-alive\r\n":"")+"User-Agent: AirPlay/550.10\r\nCSeq: "+seq+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\n\r\n";
  write(CompanionClient.join(h.getBytes("UTF-8"),body));String status=line();if(!status.matches("[^ ]+ 200 .*"))throw new IOException("HTTP status rejected: "+status);int len=0,count=0;
  for(String l;(l=line()).length()>0;){if(++count>100)throw new IOException("Header count");if(l.toLowerCase(Locale.ROOT).startsWith("content-length:"))len=Integer.parseInt(l.substring(l.indexOf(':')+1).trim());}
  if(uri.equals("/pair-verify")&&len>PairingTlv.MAX_MESSAGE)throw new IOException("Pairing response bound");
  byte[] result=readBytes(len);deadline.check();return result;
  }
 }
 public void close(){try{socket.close();}catch(Exception ignored){}if(writeKey!=null)Arrays.fill(writeKey,(byte)0);if(readKey!=null)Arrays.fill(readKey,(byte)0);}
}
