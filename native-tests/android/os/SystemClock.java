package android.os;
/** Test-only deterministic clock; excluded from APK/helper build source roots. */
public final class SystemClock { public static long now=1000; public static long elapsedRealtime(){return now;} public static long uptimeMillis(){return now;} }
