/** State-only policy. Observations must be fresh; restart never replays old transitions. */
public final class ScreenLinkPolicy {
 public enum Screen { AWAKE, ASLEEP, UNKNOWN }
 public enum Action { NONE, WAKE_TV, SLEEP_TV, SLEEP_ATV }
 private Screen previous=Screen.UNKNOWN;
 private boolean expectedOff=false;
 public Action update(Screen current, Boolean newAtvPower, boolean appleSource, int enabled) {
  if(current==Screen.UNKNOWN){previous=Screen.UNKNOWN;return Action.NONE;}
  boolean justOff=previous==Screen.AWAKE&&current==Screen.ASLEEP;
  if(current==Screen.AWAKE&&previous==Screen.ASLEEP)expectedOff=false;
  previous=current;
  if(newAtvPower!=null){
   if(newAtvPower&&current==Screen.ASLEEP&&(enabled&1)!=0)return Action.WAKE_TV;
   if(!newAtvPower){
    // An ATV-origin sleep must never be reflected back to the ATV.
    if(current==Screen.ASLEEP)expectedOff=true;
    if(current==Screen.AWAKE&&appleSource&&(enabled&2)!=0){expectedOff=true;return Action.SLEEP_TV;}
    return Action.NONE;
   }
  }
  if(justOff&&appleSource&&!expectedOff&&(enabled&4)!=0)return Action.SLEEP_ATV;
  return Action.NONE;
 }
 public void sleepTvFailed(){expectedOff=false;previous=Screen.UNKNOWN;}
}
