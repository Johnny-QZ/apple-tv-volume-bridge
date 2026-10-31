package android.net;
import java.io.*;import java.util.concurrent.*;
/** In-memory socket registry. No operating-system sockets are opened. */
public final class LocalServerSocket implements Closeable {
 static final ConcurrentHashMap<String,LocalServerSocket> REGISTRY=new ConcurrentHashMap<String,LocalServerSocket>();
 final BlockingQueue<LocalSocket> waiting=new LinkedBlockingQueue<LocalSocket>();
 final String name;volatile boolean closed;
 public LocalServerSocket(String value)throws IOException{name=value;if(REGISTRY.putIfAbsent(name,this)!=null)throw new IOException("Already bound");}
 public LocalSocket accept()throws IOException{try{LocalSocket value=waiting.take();if(closed)throw new IOException("Closed");return value;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
 public void close()throws IOException{closed=true;REGISTRY.remove(name,this);waiting.offer(new LocalSocket());}
 public static void expireAll(){for(LocalServerSocket s:REGISTRY.values())try{s.close();}catch(IOException ignored){}}
 public static int count(){return REGISTRY.size();}
}
