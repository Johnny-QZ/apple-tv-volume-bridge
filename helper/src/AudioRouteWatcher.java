import android.os.SystemClock;
import java.io.IOException;
import java.net.SocketTimeoutException;

/** Read-only MRP route subscription, limited to explicitly named headphone outputs. */
final class AudioRouteWatcher {
 static final class Snapshot {
  final boolean headphones,known;final int capability;final long verifiedAt;final CompanionConfig config;
  Snapshot(boolean h,boolean k,int c,long at,CompanionConfig value){headphones=h;known=k;capability=c;verifiedAt=at;config=value;}
 }
 static volatile Snapshot snapshot=new Snapshot(false,false,-1,0,null);
 static boolean isHeadphones(){Snapshot value=snapshot;return AudioRoutePolicy.useFine(value.headphones,value.capability,value.verifiedAt,SystemClock.uptimeMillis());}
 static CompanionConfig verifiedConfig(){Snapshot value=snapshot;return AudioRoutePolicy.useFine(value.headphones,value.capability,value.verifiedAt,SystemClock.uptimeMillis())?value.config:null;}
 // OTHER means a known non-fine route, not proof of TV-speaker output.
 static String routeKind(){Snapshot value=snapshot;long now=SystemClock.uptimeMillis();
  if(!value.known||value.verifiedAt<=0||now<value.verifiedAt||now-value.verifiedAt>=AudioRoutePolicy.FRESH_MS)return "UNKNOWN";
  return AudioRoutePolicy.useFine(value.headphones,value.capability,value.verifiedAt,now)?"HEADPHONES":"OTHER";
 }
 static void update(AirPlayRouteProbe probe,CompanionConfig config,long routeAt){
  boolean next=probe.ownUid!=null&&AudioRoutePolicy.exclusiveHeadphones(probe.names,config.headphoneNames);
  if(next!=snapshot.headphones)System.out.println("AUDIO_ROUTE "+(next?"HEADPHONES":"DEFAULT"));
  snapshot=new Snapshot(next,probe.deviceSeen&&probe.ownUid!=null&&!probe.names.isEmpty()&&probe.capability>=0,probe.capability,routeAt,config);
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
   }catch(Exception e){snapshot=new Snapshot(false,false,-1,0,null);String reason=e.getClass().getSimpleName();if(!reason.equals(lastError))System.out.println("AUDIO_ROUTE_UNKNOWN "+reason);lastError=reason;}
   finally{if(probe!=null)probe.close();}
   try{Thread.sleep(3000);}catch(InterruptedException done){return;}
  }
 }},"AudioRouteWatcher");t.setDaemon(true);t.start();}
}
