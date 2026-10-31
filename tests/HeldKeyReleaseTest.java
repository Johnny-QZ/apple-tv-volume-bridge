import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class HeldKeyReleaseTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        HeldKeyRelease normal = new HeldKeyRelease();
        check(normal.trackDown(23, 100), "initial DOWN tracked");
        normal.trackDown(23, 200);
        HeldKeyRelease.Key original = normal.claimUp(23);
        check(original != null && original.downTime == 100, "repeat retains original DOWN time");
        check(normal.claimUp(23) == null, "UP claimed only once");
        normal.trackDown(24, 300);
        AtomicInteger released = new AtomicInteger();
        normal.releaseAll((key, down) -> released.incrementAndGet());
        check(released.get() == 1 && normal.trackDown(25, 400), "normal release permits reconnect");

        final HeldKeyRelease race = new HeldKeyRelease();
        for (int key = 19; key <= 25; key++) race.trackDown(key, key * 10L);
        final Map<Integer, Integer> counts = Collections.synchronizedMap(new HashMap<Integer, Integer>());
        final HeldKeyRelease.Sender count = (key, down) -> {
            synchronized (counts) { counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1); }
        };
        CountDownLatch go = new CountDownLatch(1);
        Thread physical = new Thread(() -> {
            try { go.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            for (int key = 19; key <= 25; key++) {
                HeldKeyRelease.Key claimed = race.claimUp(key);
                if (claimed != null) try { count.keyUp(claimed.code, claimed.downTime); } catch (Exception error) { throw new AssertionError(error); }
            }
        });
        Thread cleanup = new Thread(() -> {
            try { go.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
            race.releaseAll(count);
        });
        physical.start(); cleanup.start(); go.countDown();
        race.releaseBeforeExit(count, 250);
        physical.join(1000); cleanup.join(1000);
        check(!physical.isAlive() && !cleanup.isAlive(), "cleanup threads finished");
        for (int key = 19; key <= 25; key++) check(Integer.valueOf(1).equals(counts.get(key)), "exactly one UP for " + key);
        check(race.isStopping() && !race.trackDown(26, 1000), "exit prevents later DOWN tracking");

        HeldKeyRelease blocked = new HeldKeyRelease();
        blocked.trackDown(23, 1); blocked.trackDown(24, 2);
        CountDownLatch started = new CountDownLatch(1), other = new CountDownLatch(1), unblock = new CountDownLatch(1);
        long before = System.nanoTime();
        boolean completed = blocked.releaseBeforeExit((key, down) -> {
            if (key == 23) { started.countDown(); unblock.await(); }
            else other.countDown();
        }, 100);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        check(!completed && elapsed < 1000, "blocked injection cannot prevent deadline return");
        check(started.await(1, TimeUnit.SECONDS) && other.await(1, TimeUnit.SECONDS), "blocked key does not prevent other UP");
        unblock.countDown();

        HeldKeyRelease failed = new HeldKeyRelease();
        failed.trackDown(23, 1); failed.trackDown(24, 2);
        AtomicInteger attempted = new AtomicInteger();
        check(!failed.releaseBeforeExit((key, down) -> { attempted.incrementAndGet(); throw new Exception("test rejection"); }, 250), "failed injection reported");
        check(attempted.get() == 2, "all keys attempted despite rejection");
        System.out.println("PASS single UP ownership, concurrent release, reconnect, bounded blocked injection, exit rejection");
    }
}
