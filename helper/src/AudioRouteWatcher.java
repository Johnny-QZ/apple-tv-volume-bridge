import android.os.SystemClock;
import java.io.IOException;
import java.net.SocketTimeoutException;

/** Read-only MRP route subscription, limited to explicitly named headphone outputs. */
final class AudioRouteWatcher {
 static final class Snapshot {
  final boolean headphones,known;final int capability;final long verifiedAt,generation;final CompanionConfig config;final String identity;
  Snapshot(boolean h,boolean k,int c,long at,CompanionConfig value,String id,long epoch){headphones=h;known=k;capability=c;verifiedAt=at;config=value;identity=id;generation=epoch;}
 }
 static volatile Snapshot snapshot=new Snapshot(false,false,-1,0,null,null,0);
 static Snapshot verifiedSnapshot(){
  synchronized(FineVolumeWorker.lock){Snapshot value=snapshot;long now=SystemClock.uptimeMillis();
   if(value.known&&(now<value.verifiedAt||now-value.verifiedAt>=AudioRoutePolicy.FRESH_MS)){unknown();return null;}
   return AudioRoutePolicy.useFine(value.headphones,value.capability,value.verifiedAt,now)?value:null;
  }
 }
 static boolean isHeadphones(){return verifiedSnapshot()!=null;}
 static CompanionConfig verifiedConfig(){Snapshot value=verifiedSnapshot();return value==null?null:value.config;}
 static boolean isCurrent(Snapshot expected){Snapshot value=verifiedSnapshot();return expected!=null&&value!=null&&value.generation==expected.generation&&value.config==expected.config;}
 // OTHER means a known non-fine route, not proof of TV-speaker output.
 static String routeKind(){verifiedSnapshot();Snapshot value=snapshot;
  if(!value.known)return "UNKNOWN";
  return AudioRoutePolicy.useFine(value.headphones,value.capability,value.verifiedAt,SystemClock.uptimeMillis())?"HEADPHONES":"OTHER";
 }
 static void publish(boolean headphones,boolean known,int capability,long at,CompanionConfig config,String identity){
  synchronized(FineVolumeWorker.lock){Snapshot old=snapshot;long now=SystemClock.uptimeMillis();
   boolean expired=old.known&&(now<old.verifiedAt||now-old.verifiedAt>=AudioRoutePolicy.FRESH_MS);
   boolean changed=expired||old.known!=known||old.headphones!=headphones||old.capability!=capability||old.config!=config||!java.util.Objects.equals(old.identity,identity);
   snapshot=new Snapshot(headphones,known,capability,at,config,identity,old.generation+(changed?1:0));
   if(changed)FineVolumeWorker.routeChanged();
  }
 }
 static void unknown(){publish(false,false,-1,0,null,null);}
 static void update(AirPlayRouteProbe probe,CompanionConfig config,long routeAt){
  boolean known=probe.deviceSeen&&probe.ownUid!=null&&!probe.names.isEmpty()&&probe.routeIdentity!=null&&probe.capability>=0&&routeAt>0;
  boolean next=known&&AudioRoutePolicy.exclusiveHeadphones(probe.names,config.headphoneNames);
  if(next!=snapshot.headphones)System.out.println("AUDIO_ROUTE "+(next?"HEADPHONES":"DEFAULT"));
  publish(next,known,probe.capability,routeAt,config,known?probe.routeIdentity:null);
 }
 static long receivedAt(AirPlayRouteProbe probe){return probe.routeReceivedNs<=0?0:SystemClock.uptimeMillis()-Math.max(0,(System.nanoTime()-probe.routeReceivedNs)/1000000L);}
 static void start(){Thread t=new Thread(new Runnable(){public void run(){String lastError="";
  while(true){AirPlayRouteProbe probe=null;
   try{
    CompanionConfig config=CompanionConfig.load();
    probe=new AirPlayRouteProbe(config.host,config.airplayPort,config.airplayCredentials);probe.startEvents();probe.initialize();
    long revision=probe.routeRevision,routeAt=receivedAt(probe),keepAlive=0,requestedAt=0;
    update(probe,config,routeAt);lastError="";
    while(true){int first=-1;probe.data.socket.setSoTimeout(1000);
     try{first=probe.data.read();}catch(SocketTimeoutException idle){}
     if(first>=0){probe.data.socket.setSoTimeout(4000);probe.receiveFrame(first);
      if(probe.routeRevision!=revision){revision=probe.routeRevision;routeAt=receivedAt(probe);}
      update(probe,config,routeAt);
     }
     long now=SystemClock.uptimeMillis();
     if(routeAt<=0||now-routeAt>=AudioRoutePolicy.FRESH_MS)throw new IOException("Route snapshot expired");
     if(now-routeAt>=2500&&now-requestedAt>=2500){probe.requestRouteSnapshot();requestedAt=now;}
     // A successful feedback response proves transport health, not current output identity.
     if(now-keepAlive>=1500){probe.feedback();keepAlive=now;}
    }
   }catch(Exception e){unknown();String reason=e.getClass().getSimpleName();if(!reason.equals(lastError))System.out.println("AUDIO_ROUTE_UNKNOWN "+reason);lastError=reason;}
   finally{if(probe!=null)probe.close();}
   try{Thread.sleep(3000);}catch(InterruptedException done){return;}
  }
 }},"AudioRouteWatcher");t.setDaemon(true);t.start();}
}
