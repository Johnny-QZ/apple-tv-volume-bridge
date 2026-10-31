package android.net;
import java.io.*;
/** Bidirectional bounded pipes with explicit synthetic kernel UID values. */
public final class LocalSocket implements Closeable {
 private InputStream in;private OutputStream out;private int peerUid;
 public LocalSocket(){}
 public static LocalSocket connectForTest(String name,int clientUid,int serverUid)throws IOException{
  LocalServerSocket listener=LocalServerSocket.REGISTRY.get(name);if(listener==null)throw new IOException("No listener");
  PipedInputStream clientIn=new PipedInputStream(1024),serverIn=new PipedInputStream(1024);
  LocalSocket client=new LocalSocket(),server=new LocalSocket();
  client.in=clientIn;client.out=new PipedOutputStream(serverIn);client.peerUid=serverUid;
  server.in=serverIn;server.out=new PipedOutputStream(clientIn);server.peerUid=clientUid;
  listener.waiting.add(server);return client;
 }
 public Credentials getPeerCredentials(){return new Credentials(peerUid);}
 public void setSoTimeout(int millis){}
 public InputStream getInputStream(){return in;}
 public OutputStream getOutputStream(){return out;}
 public void close()throws IOException{if(in!=null)in.close();if(out!=null)out.close();}
}
