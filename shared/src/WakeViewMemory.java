/** One sleep cycle only. Unknown or stale observations never force full screen. */
public final class WakeViewMemory {
 private boolean awake=false, observed=false, armedFull=false, restoreFull=false;
 private long armedAt=Long.MIN_VALUE;
 public synchronized void prepare(boolean fullScreen,long now){armedFull=fullScreen;armedAt=now;}
 public synchronized void observe(boolean isAwake,long now){
  if(!observed){observed=true;awake=isAwake;restoreFull=false;return;}
  if(awake&&!isAwake){restoreFull=armedFull&&armedAt!=Long.MIN_VALUE&&now>=armedAt&&now-armedAt<=5000;armedAt=Long.MIN_VALUE;armedFull=false;}
  if(!awake&&isAwake){restoreFull=false;armedAt=Long.MIN_VALUE;armedFull=false;}
  awake=isAwake;
 }
 public synchronized boolean consume(){boolean result=restoreFull;restoreFull=false;return result;}
 public synchronized void clear(){armedAt=Long.MIN_VALUE;armedFull=false;restoreFull=false;observed=false;}
}
