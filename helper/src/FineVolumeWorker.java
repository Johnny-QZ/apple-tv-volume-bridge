import android.os.SystemClock;
import java.io.*;
import java.util.*;

/** Serial volume-only worker. Never blocks the input/watchdog thread, never falls back to CEC. */
final class FineVolumeWorker {
    static final Object lock=new Object();
    static final ArrayDeque<Press> queue=new ArrayDeque<>();
    static final Set<Integer> heldKeys=new HashSet<Integer>(),blockedKeys=new HashSet<Integer>();
    static final java.util.concurrent.atomic.AtomicReference<String> notice=new java.util.concurrent.atomic.AtomicReference<String>();
    static String takeNotice(){return notice.getAndSet(null);}
    static int mode=0,generation=0,repeatGeneration=0;static long lastRepeat;
    static final class Press {final int direction,divisor,epoch,repeatEpoch;final long at;final boolean repeat;
        Press(int d,int v,int e,int re,long t,boolean r){direction=d;divisor=v;epoch=e;repeatEpoch=re;at=t;repeat=r;}}
    static boolean enabled(){synchronized(lock){return mode==3||mode==6;}}
    static void setMode(int value){synchronized(lock){int next=value==3||value==6?value:0;if(mode!=next){mode=next;generation++;queue.clear();heldKeys.clear();blockedKeys.clear();}lock.notifyAll();}}
    static void cancelPending(){synchronized(lock){queue.clear();blockedKeys.addAll(heldKeys);}}
    static void key(int key,int value){synchronized(lock){
        if(value==0){heldKeys.remove(key);blockedKeys.remove(key);repeatGeneration++;Iterator<Press> it=queue.iterator();while(it.hasNext())if(it.next().repeat)it.remove();return;}
        if(mode==0)return;
        if(value==1&&!heldKeys.add(key))return;
        if(!heldKeys.contains(key)||blockedKeys.contains(key))return;
        long now=SystemClock.uptimeMillis();if(value==2&&now-lastRepeat<200)return;lastRepeat=now;
        if(queue.size()<4){queue.add(new Press(key==24?1:-1,mode,generation,repeatGeneration,now,value==2));lock.notifyAll();}
    }}
    static void start(){Thread worker=new Thread(new Runnable(){public void run(){loop();}},"FineVolume");worker.setDaemon(true);worker.start();}
    static void loop(){CompanionClient client=null;CompanionConfig connectedConfig=null;
        while(true){Press press;
            synchronized(lock){while(queue.isEmpty())try{lock.wait();}catch(InterruptedException e){return;}press=queue.remove();}
            if(SystemClock.uptimeMillis()-press.at>700)continue;
            boolean sent=false;
            try {
                CompanionConfig config=AudioRouteWatcher.verifiedConfig();
                if(config==null)continue;
                if(client!=null&&connectedConfig!=config){client.close();client=null;}
                if(client==null){client=connect(config);connectedConfig=config;}
                double before;
                try {before=client.getVolume();}
                catch(Exception staleConnection){
                    // Only a read has been attempted: reconnect once before any write.
                    client.close();client=null;client=connect(config);before=client.getVolume();
                }
                synchronized(lock){if(press.epoch!=generation||(press.repeat&&press.repeatEpoch!=repeatGeneration)||AudioRouteWatcher.verifiedConfig()!=config||SystemClock.uptimeMillis()-press.at>1500)continue;}
                double target=Math.max(0,Math.min(100,before+press.direction*6.25/press.divisor));
                if(Math.abs(target-before)<0.0001)continue;
                sent=true;client.setVolume(target);
                double after=client.getVolume();
                System.out.println("FINE_VOLUME "+press.divisor+" "+before+" "+target+" "+after+" "+(Math.abs(after-target)<=.05?"MATCHED":"UNCONFIRMED"));
                if(Math.abs(after-target)>.05){notice.set("UNCERTAIN");cancelPending();}
            }catch(Exception error){
                if(client!=null)client.close();client=null;
                cancelPending();
                // A write may already have taken effect. Do not retry or forward to CEC.
                notice.set(sent?"UNCERTAIN":"UNAVAILABLE");
                System.out.println("FINE_VOLUME_"+(sent?"UNCERTAIN":"UNAVAILABLE")+" "+error.getClass().getSimpleName());
                if(sent)try{CompanionConfig config=AudioRouteWatcher.verifiedConfig();if(config!=null){client=connect(config);connectedConfig=config;System.out.println("FINE_VOLUME_READBACK "+client.getVolume());}}catch(Exception ignored){if(client!=null)client.close();client=null;}
            }
        }
    }
    static CompanionClient connect(CompanionConfig config)throws Exception {
        return new CompanionClient(config.host,config.companionPort,config.companionCredentials);
    }
}
