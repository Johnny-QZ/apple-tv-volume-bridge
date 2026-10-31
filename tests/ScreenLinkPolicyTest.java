public class ScreenLinkPolicyTest {
 static void eq(Object a,Object b){if(!a.equals(b))throw new AssertionError(a+" != "+b);}
 public static void main(String[] args){
  ScreenLinkPolicy p=new ScreenLinkPolicy();
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,null,true,7),ScreenLinkPolicy.Action.NONE);
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,true,true,7),ScreenLinkPolicy.Action.WAKE_TV);
  eq(p.update(ScreenLinkPolicy.Screen.AWAKE,null,true,7),ScreenLinkPolicy.Action.NONE);
  eq(p.update(ScreenLinkPolicy.Screen.AWAKE,false,true,7),ScreenLinkPolicy.Action.SLEEP_TV);
  eq(p.update(ScreenLinkPolicy.Screen.AWAKE,null,true,7),ScreenLinkPolicy.Action.NONE);
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,null,true,7),ScreenLinkPolicy.Action.NONE);
  p.update(ScreenLinkPolicy.Screen.AWAKE,null,true,7);
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,null,true,7),ScreenLinkPolicy.Action.SLEEP_ATV);
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,null,true,7),ScreenLinkPolicy.Action.NONE);
  p.update(ScreenLinkPolicy.Screen.AWAKE,null,false,7);
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,null,false,7),ScreenLinkPolicy.Action.NONE);
  p.update(ScreenLinkPolicy.Screen.AWAKE,null,true,7);
  p.update(ScreenLinkPolicy.Screen.UNKNOWN,null,true,7);
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,null,true,7),ScreenLinkPolicy.Action.NONE);
  p.update(ScreenLinkPolicy.Screen.AWAKE,null,true,0);
  eq(p.update(ScreenLinkPolicy.Screen.AWAKE,false,true,0),ScreenLinkPolicy.Action.NONE);
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,null,true,0),ScreenLinkPolicy.Action.NONE);
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,true,true,0),ScreenLinkPolicy.Action.NONE);
  p.update(ScreenLinkPolicy.Screen.AWAKE,null,true,7);
  eq(p.update(ScreenLinkPolicy.Screen.ASLEEP,false,true,7),ScreenLinkPolicy.Action.NONE);
  for(int mask=0;mask<8;mask++){
   ScreenLinkPolicy q=new ScreenLinkPolicy();
   q.update(ScreenLinkPolicy.Screen.ASLEEP,null,true,mask);
   eq(q.update(ScreenLinkPolicy.Screen.ASLEEP,true,true,mask),(mask&1)!=0?ScreenLinkPolicy.Action.WAKE_TV:ScreenLinkPolicy.Action.NONE);
   q=new ScreenLinkPolicy();q.update(ScreenLinkPolicy.Screen.AWAKE,null,true,mask);
   eq(q.update(ScreenLinkPolicy.Screen.AWAKE,false,true,mask),(mask&2)!=0?ScreenLinkPolicy.Action.SLEEP_TV:ScreenLinkPolicy.Action.NONE);
   q=new ScreenLinkPolicy();q.update(ScreenLinkPolicy.Screen.AWAKE,null,true,mask);
   eq(q.update(ScreenLinkPolicy.Screen.ASLEEP,null,true,mask),(mask&4)!=0?ScreenLinkPolicy.Action.SLEEP_ATV:ScreenLinkPolicy.Action.NONE);
  }
  System.out.println("PASS: restart, unknown state, three directions, no echo, source guard, switches");
 }
}
