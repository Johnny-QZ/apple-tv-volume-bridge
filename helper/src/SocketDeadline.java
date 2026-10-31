import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;

/** A whole-operation deadline: closing the socket also interrupts blocked writes. */
final class SocketDeadline implements Closeable {
    private static final ScheduledThreadPoolExecutor TIMER = timer();
    private static ScheduledThreadPoolExecutor timer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            public Thread newThread(Runnable task) { Thread t=new Thread(task,"BridgeNetworkDeadline");t.setDaemon(true);return t; }
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
    private final Socket socket;
    private final long end;
    private final ScheduledFuture<?> alarm;
    private boolean finished, expired;
    SocketDeadline(Socket value,long millis) {
        if(millis<=0)throw new IllegalArgumentException("Deadline must be positive");
        socket=value;end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        alarm=TIMER.schedule(new Runnable(){public void run(){expire();}},millis,TimeUnit.MILLISECONDS);
    }
    private void expire() {
        synchronized(this){if(finished)return;expired=true;finished=true;}
        try{socket.close();}catch(IOException ignored){}
    }
    synchronized int remainingMillis() throws SocketTimeoutException {
        long left=end-System.nanoTime();
        if(expired||left<=0)throw new SocketTimeoutException("Network operation deadline");
        return (int)Math.max(1,Math.min(Integer.MAX_VALUE,TimeUnit.NANOSECONDS.toMillis(left)));
    }
    void check() throws SocketTimeoutException {remainingMillis();}
    public synchronized void close(){finished=true;alarm.cancel(false);}
    static int pendingForTests(){return TIMER.getQueue().size();}
}
