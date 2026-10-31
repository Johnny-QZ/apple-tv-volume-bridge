package local.cecprobe;
import android.app.*;
import android.os.*;
import android.content.*;
import android.media.tv.*;
import android.media.AudioManager;
import android.view.*;
import android.widget.*;
import android.graphics.Color;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.*;
public class MainActivity extends Activity {
 private TvView tv; private TextView status;
 private AudioManager audioManager; private boolean audioFocused=false;
 private Boolean hardwareMuted=null; private boolean hardwareAudioErrorLogged=false;
 private final AudioManager.OnAudioFocusChangeListener audioFocusListener=new AudioManager.OnAudioFocusChangeListener(){
  public void onAudioFocusChange(final int change){handler.post(new Runnable(){public void run(){
   audioFocused=change==AudioManager.AUDIOFOCUS_GAIN;
   if(tv!=null&&resumed){tv.setStreamVolume(audioFocused?1.0f:0.0f);if(audioFocused)syncHdmiAudio(true);}
  }});}
 };
 private void acquireAudio(){
  if(audioManager==null)audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
  audioFocused=audioManager.requestAudioFocus(audioFocusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
  tv.setStreamVolume(audioFocused?1.0f:0.0f);
  syncHdmiAudio(true);
  Log.i("CECBridge","HDMI audio focus "+audioFocused);
 }
 private void syncHdmiAudio(boolean force){
  if(!resumed||!audioFocused||audioManager==null)return;
  boolean muted=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)==0||audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
  if(!force&&hardwareMuted!=null&&hardwareMuted.booleanValue()==muted)return;
  try{
   // Matches Xiaomi TvViewManagerImpl.setVolumeIndex: separate TV hardware mute.
   audioManager.setParameters(muted?"TV-Mute=1":"TV-Mute=0");
   hardwareMuted=muted;Log.i("CECBridge","HDMI hardware mute "+muted);
  }catch(Exception e){if(!hardwareAudioErrorLogged){hardwareAudioErrorLogged=true;Log.w("CECBridge","HDMI hardware audio restore",e);}}
 }
 private void releaseAudio(){
  if(tv!=null)tv.setStreamVolume(0.0f);
  if(audioManager!=null)audioManager.abandonAudioFocus(audioFocusListener);
  audioFocused=false;hardwareMuted=null;
 }
 private volatile boolean ready=false,resumed=false,focused=false,closing=false,direct=false,starting=false;
 private volatile boolean helperFailed=false;
 private long resumedAt=0; private long lastFineNotice=0;
 private volatile long lastHelper=0; private long lastStart=0; private int retries=0,generation=0;
 private volatile ServerSocket server; private volatile Socket client;
 private Handler handler=new Handler(); private TvInputManager inputs;
 private String input=BridgeConfig.INPUT_ID;
 private volatile int powerMask=0;
 private volatile int fineDivisor=0;
 private volatile String currentRoute="UNKNOWN";
 private volatile int reportedFineDivisor=-1;
 private volatile long lastRouteReport=0;
 private AlertDialog volumeMenu;
 private String startupStatus="正在恢复音量控制…";
 private byte[] bridgeKey;
 private String fatalStatus;
 private final TvInputManager.TvInputCallback inputCallback=new TvInputManager.TvInputCallback(){
  public void onInputAdded(String id){if(resumed&&!ready){retries=0;scheduleTune(700);}}
  public void onInputRemoved(String id){if(id.equals(input)){ready=false;renderStatus();scheduleTune(1500);}}
  public void onInputStateChanged(String id,int state){if(resumed&&id.equals(input)&&state==TvInputManager.INPUT_STATE_CONNECTED&&!ready){retries=0;scheduleTune(700);}}
 };
 public void onCreate(Bundle saved){
  super.onCreate(saved);fineDivisor=getSharedPreferences("volume",0).getInt("divisor",0);if(fineDivisor!=3&&fineDivisor!=6)fineDivisor=0;powerMask=getSharedPreferences("screen_link",0).getInt("mask",0)&7;getWindow().setFlags(1024,1024);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  FrameLayout root=new FrameLayout(this);tv=new TvView(this);root.addView(tv,new FrameLayout.LayoutParams(-1,-1));
  status=new TextView(this);status.setVisibility(View.GONE);status.setTextSize(19);status.setTextColor(Color.WHITE);status.setBackgroundColor(0xC0000000);status.setPadding(24,12,24,12);
  FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);root.addView(status,p);setContentView(root);tv.setFocusable(true);tv.requestFocus();
  inputs=(TvInputManager)getSystemService(TV_INPUT_SERVICE);inputs.registerCallback(inputCallback,handler);
  tv.setCallback(new TvView.TvInputCallback(){
   public void onVideoAvailable(String id){if(resumed&&id.equals(input)){ready=true;retries=0;tv.setStreamVolume(audioFocused?1.0f:0.0f);syncHdmiAudio(true);handler.postDelayed(new Runnable(){public void run(){if(ready)syncHdmiAudio(true);}},300);Log.i("CECBridge","video available "+id);renderStatus();}}
   public void onVideoUnavailable(String id,int reason){Log.i("CECBridge","video unavailable "+reason);}
   public void onConnectionFailed(String id){if(id.equals(input)){ready=false;Log.e("CECBridge","connection failed");scheduleTune(1500);}}
   public void onDisconnected(String id){if(id.equals(input)){ready=false;Log.e("CECBridge","disconnected");scheduleTune(1500);}}
  });
  try{bridgeKey=BridgeSecrets.load(this);}catch(Exception e){fatalStatus="无法读取本机认证密钥，请检查应用数据。";showStatus(fatalStatus);Log.e("CECBridge","bridge identity unavailable",e);return;}
  new Thread(new Runnable(){public void run(){serve();}},"CECBridgeSocket").start();
  handler.post(tick);
 }
 private boolean eligible(){return resumed&&focused&&ready&&!closing;}
 private void showStatus(String text){status.setText(text);status.setVisibility(View.VISIBLE);}
 private void renderStatus(){
  if(fatalStatus!=null){showStatus(fatalStatus);return;}
  if(!resumed||SystemClock.uptimeMillis()-resumedAt<15000){status.setVisibility(View.GONE);return;}
  if(!ready){
   if(retries>=4)showStatus("未能恢复 Apple TV 连接。按菜单键选择“重新连接”。");
   else status.setVisibility(View.GONE);
   return;
  }
  if(helperFailed&&SystemClock.uptimeMillis()-lastHelper>3000){showStatus(startupStatus);return;}
  status.setVisibility(View.GONE);
 }
 private final Runnable tick=new Runnable(){public void run(){if(closing)return;
  if(resumed){if(ready)syncHdmiAudio(false);renderStatus();refreshVolumeMenu();if(SystemClock.uptimeMillis()-lastHelper>3500&&!starting&&SystemClock.uptimeMillis()-lastStart>15000)startHelper();}
  handler.postDelayed(this,1000);
 }};
 private void startHelper(){
  if(bridgeKey==null||closing)return;
  starting=true;helperFailed=false;lastStart=SystemClock.uptimeMillis();startupStatus="正在恢复音量控制…";
  new Thread(new Runnable(){public void run(){
   try{ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=getAssets().open("bridge-helper.jar");byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);in.close();
    LocalAdb.installAndStart(MainActivity.this,out.toByteArray(),bridgeKey,new LocalAdb.Status(){public void update(final String message){handler.post(new Runnable(){public void run(){startupStatus=message;renderStatus();}});}});
    handler.post(new Runnable(){public void run(){startupStatus="辅助程序已启动，正在连接…";}});
   }catch(final Exception e){helperFailed=true;Log.w("CECBridge","local ADB startup",e);handler.post(new Runnable(){public void run(){startupStatus="音量控制未启动：请打开电视的 ADB 调试；若有授权弹窗，请选择允许。菜单可重试。";renderStatus();}});}
   finally{starting=false;}
  }},"CECBridgeStart").start();
 }
 private String chooseInput(){
  List<TvInputInfo> list=inputs.getTvInputList();
  for(TvInputInfo info:list){String id=info.getId();String label=String.valueOf(info.loadLabel(this));if(id.contains("/HDMI")&&label.toLowerCase(Locale.ROOT).contains("apple"))return id;}
  for(TvInputInfo info:list)if(info.getId().equals(input))return input;
  return null;
 }
 private void scheduleTune(long delay){
  if(!resumed||closing||retries>=4)return;
  final int g=++generation;
  handler.postDelayed(new Runnable(){public void run(){if(resumed&&!closing&&g==generation)tune();}},delay);
 }
 private void tune(){
  if(!resumed||closing)return;retries++;ready=false;renderStatus();String selected=chooseInput();
  if(selected==null){Log.w("CECBridge","Apple TV input absent");scheduleTune(2000);return;}
  input=selected;
  try{tv.reset();tv.tune(input,TvContract.buildChannelUriForPassthroughInput(input));Log.i("CECBridge","tune attempt "+retries+" "+input);}catch(Exception e){Log.w("CECBridge","tune",e);scheduleTune(1500);}
  final int g=generation;handler.postDelayed(new Runnable(){public void run(){if(resumed&&!ready&&g==generation)scheduleTune(500);}},8000);
 }
 private void serve(){
  while(!closing){
   try{
    ServerSocket listener=new ServerSocket();listener.setReuseAddress(true);listener.bind(new InetSocketAddress("127.0.0.1",BridgeConfig.PORT));server=listener;Log.i("CECBridge","listener ready");
    while(!closing){Socket s=listener.accept();client=s;s.setTcpNoDelay(true);s.setSoTimeout(2500);
     try{InputStream r=s.getInputStream();OutputStream out=s.getOutputStream();
      BridgeAuth.accept(r,out,bridgeKey);PrintWriter w=new PrintWriter(new OutputStreamWriter(out,"US-ASCII"),true);String line;
      while(!closing&&(line=BridgeWire.readLine(r))!=null){
       lastHelper=SystemClock.uptimeMillis();
       if(line.equals("STATE")){w.println((eligible()?(direct?"DIRECT":"ACTIVE"):"INACTIVE")+" "+powerMask+" "+fineDivisor);continue;}
       if(line.startsWith("FINE_ERROR ")){final boolean uncertain=line.endsWith("UNCERTAIN");handler.post(new Runnable(){public void run(){long now=SystemClock.uptimeMillis();if(now-lastFineNotice>5000){lastFineNotice=now;Toast.makeText(MainActivity.this,uncertain?"音量结果未确认，已停止重复调节":"耳机精调暂时无法连接；可在菜单切回自动",Toast.LENGTH_LONG).show();}}});w.println("OK");continue;}
       String[] parts=line.split(" ");
       if(parts.length==3&&parts[0].equals("ROUTE")){
        String route=parts[1];int selected=Integer.parseInt(parts[2]);
        if((route.equals("HEADPHONES")||route.equals("OTHER")||route.equals("UNKNOWN"))&&(selected==0||selected==3||selected==6)){
         currentRoute=route;reportedFineDivisor=selected;lastRouteReport=SystemClock.uptimeMillis();
         handler.post(new Runnable(){public void run(){refreshVolumeMenu();}});w.println("OK");continue;
        }
       }
       if(parts.length==4&&parts[0].equals("KEY")&&eligible()){
        final int code=Integer.parseInt(parts[1]),action=Integer.parseInt(parts[2]),repeat=Integer.parseInt(parts[3]);
        if((code==24||code==25)&&(action==0||action==1)&&repeat>=0&&repeat<=100000){
         handler.post(new Runnable(){public void run(){if(eligible()){long now=SystemClock.uptimeMillis();tv.dispatchKeyEvent(new KeyEvent(now,now,action,code,repeat));Log.i("CECBridge","volume "+code+" action "+action);}}});w.println("OK");continue;
        }
       }w.println("IGNORED");
      }
     }catch(Exception e){if(!closing)Log.i("CECBridge","helper disconnected: "+e.getClass().getSimpleName());}
     finally{lastHelper=0;currentRoute="UNKNOWN";reportedFineDivisor=-1;lastRouteReport=0;handler.post(new Runnable(){public void run(){refreshVolumeMenu();}});try{s.close();}catch(Exception e){}if(client==s)client=null;}
    }
   }catch(Exception e){if(!closing)Log.w("CECBridge","listener retry",e);}
   finally{try{if(server!=null)server.close();}catch(Exception e){}server=null;}
   if(!closing)try{Thread.sleep(1000);}catch(InterruptedException e){return;}
  }
 }
 public boolean dispatchKeyEvent(KeyEvent e){
  int k=e.getKeyCode();if(k==82){if(e.getAction()==1)showMenu();return true;}
  if(k==19||k==20||k==21||k==22||k==23||k==66||k==4||k==85||k==86||k==87||k==88||k==89||k==90){if(ready){tv.dispatchKeyEvent(e);return true;}}
  return super.dispatchKeyEvent(e);
 }
 private String volumeMenuTitle(){
  String title="Apple TV 音量转发\n当前：";
  if(direct)return title+"电视扬声器直控";
  if(SystemClock.uptimeMillis()-lastRouteReport>=2500||lastRouteReport==0||currentRoute.equals("UNKNOWN"))return title+"输出状态未知，使用默认音量";
  if(reportedFineDivisor!=fineDivisor)return title+"正在更新音量模式";
  if(currentRoute.equals("HEADPHONES")&&(fineDivisor==3||fineDivisor==6))return title+"耳机精调 1/"+fineDivisor;
  return title+(currentRoute.equals("HEADPHONES")?"默认音量（耳机精调未启用）":"默认音量（其他输出）");
 }
 private void refreshVolumeMenu(){if(volumeMenu!=null&&volumeMenu.isShowing())volumeMenu.setTitle(volumeMenuTitle());}
 private void showMenu(){
  String[] options={"偏好：始终使用默认音量"+(!direct&&fineDivisor==0?" ✓":""),"耳机偏好：1/3 精调（其他输出用默认）"+(fineDivisor==3?" ✓":""),"耳机偏好：1/6 精调（其他输出用默认）"+(fineDivisor==6?" ✓":""),"电视扬声器直控（低延迟）"+(direct?" ✓":""),"重新连接 / 恢复音量控制","息屏联动设置","退出视频转发"};
  volumeMenu=new AlertDialog.Builder(this).setTitle(volumeMenuTitle()).setItems(options,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
   if(which==6){finish();return;}if(which==5){showPowerMenu();return;}if(which==4){retries=0;scheduleTune(300);if(!starting){lastStart=0;startHelper();}return;}
   if(which==1||which==2){direct=false;fineDivisor=which==1?3:6;saveVolumeMode();return;}
   fineDivisor=0;direct=which==3;saveVolumeMode();Toast.makeText(MainActivity.this,direct?"电视扬声器直控；使用耳机前请切回自动":"自动：由 Apple TV 按当前输出调节",Toast.LENGTH_LONG).show();
  }}).show();
 }
 private void saveVolumeMode(){getSharedPreferences("volume",0).edit().putInt("divisor",fineDivisor).apply();}
 private void showPowerMenu(){
  final String[] labels={"Apple TV 唤醒 → 电视亮屏","Apple TV 休眠 → 电视息屏","电视息屏 → Apple TV 休眠"};
  boolean[] checked={(powerMask&1)!=0,(powerMask&2)!=0,(powerMask&4)!=0};
  new AlertDialog.Builder(this).setTitle("息屏联动（测试版）")
   .setMultiChoiceItems(labels,checked,new DialogInterface.OnMultiChoiceClickListener(){public void onClick(DialogInterface d,int which,boolean yes){
    int bit=1<<which;powerMask=yes?(powerMask|bit):(powerMask&~bit);getSharedPreferences("screen_link",0).edit().putInt("mask",powerMask).apply();
   }}).setPositiveButton("完成",null).show();
 }
 public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);focused=hasFocus;Log.i("CECBridge","window focus "+hasFocus);}
 protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);retries=0;scheduleTune(300);}
 protected void onResume(){super.onResume();resumed=true;acquireAudio();resumedAt=SystemClock.uptimeMillis();retries=0;scheduleTune(250);renderStatus();Log.i("CECBridge","resume");}
 protected void onPause(){resumed=false;releaseAudio();ready=false;generation++;tv.reset();super.onPause();Log.i("CECBridge","pause and release");}
 protected void onDestroy(){closing=true;resumed=false;ready=false;generation++;handler.removeCallbacksAndMessages(null);inputs.unregisterCallback(inputCallback);try{if(client!=null)client.close();}catch(Exception e){}try{if(server!=null)server.close();}catch(Exception e){}tv.reset();super.onDestroy();}
}
