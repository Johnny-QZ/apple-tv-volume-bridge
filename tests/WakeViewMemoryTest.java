public class WakeViewMemoryTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  WakeViewMemory m=new WakeViewMemory();m.observe(true,0);m.prepare(true,100);m.observe(false,200);check(m.consume());check(!m.consume());
  m.observe(true,300);m.prepare(false,400);m.observe(false,500);check(!m.consume());
  m.observe(true,600);m.prepare(true,700);m.observe(false,6000);check(!m.consume());
  m.observe(true,7000);m.prepare(true,7100);m.observe(false,7200);m.observe(true,7300);m.observe(false,7400);check(!m.consume());
  m.clear();m.observe(false,8000);check(!m.consume());
  System.out.println("PASS full-screen, home/unknown, stale snapshot, manual wake, restart, consume-once");
 }
}
