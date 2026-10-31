package android.net;
/** Synthetic peer credentials for offline tests; never included in production. */
public final class Credentials {
 private final int uid;
 public Credentials(int value){uid=value;}
 public int getUid(){return uid;}
}
