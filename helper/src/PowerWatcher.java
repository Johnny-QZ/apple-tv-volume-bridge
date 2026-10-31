import java.util.*;
import java.util.regex.*;
import java.io.*;
import android.net.LocalServerSocket;
import android.os.SystemClock;

/** Screen-only power linkage for the verified Xiaomi firmware. */
public final class PowerWatcher {
 static final String AUTO="hdmi_control_auto_device_off_enabled";
 static final File MARKER=new File("/data/local/tmp/cec-bridge-v3-power-restore");
 static final Pattern ROUTE=Pattern.compile("mActiveRoutingPath:\\s*0x([0-9a-fA-F]+)");
 static Set<String> seen=new HashSet<String>();
 static boolean appleSource=false;
 static final WakeViewMemory wakeView=new WakeViewMemory();
 static void captureBeforeSleep(){
  boolean full=false;
  try{
   String activity=run("/system/bin/dumpsys","activity","activities");
   String foreground="";
   for(String line:activity.split("\\n"))if(line.contains("mResumedActivity"))foreground=line;
   full=foreground.contains("local.cecprobe/");
  }catch(Exception ignored){}
  wakeView.prepare(full,SystemClock.elapsedRealtime());
  log("SLEEP_VIEW "+(full?"FULLSCREEN":"PRESERVE_SYSTEM"));
 }
 static String run(String... args)throws Exception{
  LinkCommand.Result r=LinkCommand.command(args);
  if(!r.ok||r.text.contains("SecurityException")||r.text.contains("Error:"))throw new IOException(r.reason+" "+r.text);
  return r.text;
 }
 static String hdmi()throws Exception{return run("/system/bin/dumpsys","hdmi_control");}
 static String setting(String name)throws Exception{return run("/system/bin/settings","get","global",name).trim();}
 static void put(String name,String value)throws Exception{run("/system/bin/settings","put","global",name,value);}
 static void log(String text){System.out.println(SystemClock.elapsedRealtime()+" "+text);}
 static ScreenLinkPolicy.Screen screen()throws Exception{
  String s=run("/system/bin/dumpsys","power");
  if(s.contains("mWakefulness=Awake"))return ScreenLinkPolicy.Screen.AWAKE;
  if(s.contains("mWakefulness=Asleep"))return ScreenLinkPolicy.Screen.ASLEEP;
  return ScreenLinkPolicy.Screen.UNKNOWN;
 }
 static Boolean messages(String dump, boolean baseline){
  Boolean event=null;Set<String> current=new HashSet<String>();
  for(String line:dump.split("\\n")){
   line=line.trim();if(!line.contains("[R]"))continue;current.add(line);
   if(baseline||seen.contains(line))continue;
   if(line.matches(".*message=<Standby> src: 4, dst: (0|15)\\s*"))event=false;
   else if(line.matches(".*message=<(Image View On|Text View On)> src: 4, dst: 0\\s*")||line.matches(".*message=<Active Source> src: 4, dst: 15, params: 20 00\\s*"))event=true;
  }
  seen=current;return event;
 }
 static boolean toggle(ScreenLinkPolicy.Screen desired)throws Exception{
  ScreenLinkPolicy.Screen s=screen();if(s==desired)return true;
  if(s==ScreenLinkPolicy.Screen.UNKNOWN)return false;
  if(!run("/system/bin/settings","get","system","mitv.short.power.action").trim().equals("0"))throw new IOException("short power no longer screen-off");
  run("/system/bin/input","keyevent","26");log("TV_REQUEST "+desired);return true;
 }
 static void sleepAtv()throws Exception{
  if(!setting(AUTO).equals("0"))throw new IOException("original auto-off is not disabled");
  String before=hdmi();
  try{
   try(FileOutputStream out=new FileOutputStream(MARKER)){out.write('0');out.getFD().sync();}
   put(AUTO,"1");
   long ready=SystemClock.elapsedRealtime()+2500;
   while(!hdmi().contains("mAutoDeviceOff: true")){
    if(SystemClock.elapsedRealtime()>ready)throw new IOException("auto-off setting did not apply");Thread.sleep(100);
   }
   run("/system/bin/am","broadcast","--user","0","-p","android","-a","mitv.goto.str_mode");
   long end=SystemClock.elapsedRealtime()+8000;boolean sent=false;
   while(SystemClock.elapsedRealtime()<end){
    String after=hdmi();
    for(String line:after.split("\\n"))if(line.contains("[S]")&&line.contains("message=<Standby> src: 0, dst: 15")&&!before.contains(line)){sent=true;break;}
    if(sent)break;Thread.sleep(150);
   }
   if(!sent)throw new IOException("no new CEC Standby transmission observed");
   log("ATV_STANDBY_SENT");
  }finally{
   put(AUTO,"0");if(!setting(AUTO).equals("0"))throw new IOException("restore failed");MARKER.delete();
  }
 }
 static volatile int enabled=0;
 static final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean(false);
 static void setEnabled(int mask){enabled=mask&7;}
 static void start(){
  if(!started.compareAndSet(false,true))return;
  Thread t=new Thread(new Runnable(){public void run(){try{watch();}catch(Throwable e){log("STOP "+e);}finally{started.set(false);}}},"CECPowerWatcher");
  t.setDaemon(true);t.start();
 }
 static void watch()throws Exception{
  LocalServerSocket lock=new LocalServerSocket("local.cecprobe.powerwatcher");
  if(MARKER.exists()){put(AUTO,"0");MARKER.delete();log("RECOVERED_SETTING");}
  ScreenLinkPolicy policy=new ScreenLinkPolicy();
  String initial=hdmi();messages(initial,true);
  Matcher startRoute=ROUTE.matcher(initial);if(startRoute.find())appleSource=Integer.parseInt(startRoute.group(1),16)==0x2000;
  ScreenLinkPolicy.Screen initialScreen=screen();
  policy.update(initialScreen,null,appleSource,enabled);
  if(initialScreen!=ScreenLinkPolicy.Screen.UNKNOWN)wakeView.observe(initialScreen==ScreenLinkPolicy.Screen.AWAKE,SystemClock.elapsedRealtime());
  log("POWER_WATCHER_READY");
  String error="";int previousMask=enabled;
  try{
   while(!Thread.currentThread().isInterrupted()){
    long tick=SystemClock.elapsedRealtime();
    try{
     String dump=hdmi();ScreenLinkPolicy.Screen current=screen();Boolean event=messages(dump,false);
     if(current==ScreenLinkPolicy.Screen.UNKNOWN)wakeView.clear();
     else wakeView.observe(current==ScreenLinkPolicy.Screen.AWAKE,SystemClock.elapsedRealtime());
     Matcher route=ROUTE.matcher(dump);if(route.find()){
      int r=Integer.parseInt(route.group(1),16);
      if(r==0x2000)appleSource=true;
      else if(r>=0x1000&&r<=0x4000)appleSource=false;
      else if(current==ScreenLinkPolicy.Screen.AWAKE&&r==0)appleSource=false;
     }
     if(Boolean.TRUE.equals(event))appleSource=true;
     int mask=enabled;
     if(mask!=previousMask){policy=new ScreenLinkPolicy();policy.update(current,null,appleSource,mask);event=null;previousMask=mask;log("POWER_SWITCHES "+mask);}
     ScreenLinkPolicy.Action a=policy.update(current,event,appleSource,mask);
     if(a==ScreenLinkPolicy.Action.WAKE_TV){
      boolean restoreFull=wakeView.consume();
      if(toggle(ScreenLinkPolicy.Screen.AWAKE)){
       long wakeDeadline=SystemClock.elapsedRealtime()+3000;
       while(screen()!=ScreenLinkPolicy.Screen.AWAKE&&SystemClock.elapsedRealtime()<wakeDeadline)Thread.sleep(100);
       if(restoreFull&&screen()==ScreenLinkPolicy.Screen.AWAKE){run("/system/bin/am","start","-n","local.cecprobe/.MainActivity");log("RESTORE_APP_AFTER_WAKE");}
      }
     }
     else if(a==ScreenLinkPolicy.Action.SLEEP_TV){captureBeforeSleep();if(!toggle(ScreenLinkPolicy.Screen.ASLEEP))policy.sleepTvFailed();}
     else if(a==ScreenLinkPolicy.Action.SLEEP_ATV)sleepAtv();
     error="";
    }catch(Exception ex){policy.sleepTvFailed();String e=ex.toString();if(!e.equals(error))log("WAIT "+e);error=e;}
    Thread.sleep(Math.max(100,1000-(SystemClock.elapsedRealtime()-tick)));
   }
  }finally{if(MARKER.exists()){put(AUTO,"0");MARKER.delete();}lock.close();log("POWER_WATCHER_STOPPED");}
 }
}
