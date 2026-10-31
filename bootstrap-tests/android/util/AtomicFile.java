package android.util;
import java.io.*;import java.nio.file.*;
public final class AtomicFile {
 private final File file;public AtomicFile(File value){file=value;}public File getBaseFile(){return file;}
 public byte[] readFully()throws IOException{return Files.readAllBytes(file.toPath());}
 public FileOutputStream startWrite()throws IOException{return new FileOutputStream(file);}
 public void finishWrite(FileOutputStream out)throws IOException{out.close();}
 public void failWrite(FileOutputStream out){try{out.close();}catch(IOException ignored){}}
 public void delete(){file.delete();new File(file+".bak").delete();}
}
