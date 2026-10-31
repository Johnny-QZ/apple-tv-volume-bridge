/** Raw key ownership, independent of Android's injected-event repeat policy. */
public final class ConfirmHold {
 public static boolean handles(int key){return (key>=19&&key<=23)||key==66;}
 public int key, repeat;
 public long down, next;
 public void press(int code,long now){
  if(key==code)return;
  key=code;down=now;repeat=0;next=now+300;
 }
 public void release(int code){if(key==code)clear();}
 public void clear(){key=0;repeat=0;down=0;next=0;}
 public boolean poll(long now){
  if(key==0||now<next)return false;
  repeat++;next=now+150;return true;
 }
}
