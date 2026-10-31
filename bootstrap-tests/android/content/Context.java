package android.content;
import java.io.File;
public class Context {private final File dir;public Context(File value){dir=value;}public File getNoBackupFilesDir(){return dir;}}
