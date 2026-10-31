public final class ConfirmHoldTest {
 public static void main(String[] args){
  ConfirmHold h=new ConfirmHold();
  h.press(23,1000);
  if(h.poll(1200))throw new AssertionError("short press repeated");
  h.release(23);
  if(h.poll(1500))throw new AssertionError("repeat after release");
  h.press(23,2000);int count=0;
  for(long t=2000;t<7000;t+=10){
   h.press(23,t); // duplicate raw downs must not reset the hold
   if(h.poll(t)){count++;if(h.down!=2000)throw new AssertionError("down time changed");}
  }
  if(count<30||count>33)throw new AssertionError("hold not sustained: "+count);
  h.release(23);if(h.poll(8000))throw new AssertionError("release ignored");
  h.press(23,9000);h.clear();if(h.poll(9500))throw new AssertionError("cancel ignored");
  h.press(23,10000);h.poll(20000);
  if(h.poll(20000))throw new AssertionError("burst catch-up");
  for(int key:new int[]{19,20,21,22,23,66}){
   if(!ConfirmHold.handles(key))throw new AssertionError("unmanaged direction/confirm");
   h.clear();h.press(key,0);int repeats=0;
   for(long now=0;now<10000;now+=10)if(h.poll(now))repeats++;
   if(repeats<60)throw new AssertionError("ten second hold ended: "+key);
   h.release(key);if(h.poll(11000))throw new AssertionError("direction stuck after release");
  }
  for(int key:new int[]{3,4,24,25,26,82})if(ConfirmHold.handles(key))throw new AssertionError("unrelated key changed");
  h.press(20,12000);h.press(19,13000);h.release(20);
  if(h.key!=19||!h.poll(13300))throw new AssertionError("old release cancelled new direction");
  h.clear();if(h.poll(14000))throw new AssertionError("focus loss left hold active");
  System.out.println("PASS short tap, five-second hold, duplicate down, release, cancel, no burst");
 }
}
