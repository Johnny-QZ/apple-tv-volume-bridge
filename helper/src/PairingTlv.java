import java.io.*;
import java.util.*;

/** Small, bounded pair-verify TLV messages; fragments are accumulated in linear time. */
final class PairingTlv {
    static final int MAX_MESSAGE=16384, MAX_FIELD=8192, MAX_FRAGMENTS=256;
    static Map<Integer,byte[]> decode(byte[] data)throws IOException {
        if(data.length>MAX_MESSAGE)throw new IOException("Pairing message bound");
        Map<Integer,ByteArrayOutputStream> fields=new LinkedHashMap<Integer,ByteArrayOutputStream>();
        int fragments=0;
        for(int p=0;p<data.length;){
            if(++fragments>MAX_FRAGMENTS||p+2>data.length)throw new IOException("Pairing fragment bound");
            int type=data[p++]&255,length=data[p++]&255;
            if(length>data.length-p)throw new EOFException("Truncated pairing field");
            ByteArrayOutputStream field=fields.get(type);
            if(field==null){field=new ByteArrayOutputStream();fields.put(type,field);}
            if(field.size()+length>MAX_FIELD)throw new IOException("Pairing field bound");
            field.write(data,p,length);p+=length;
        }
        Map<Integer,byte[]> result=new LinkedHashMap<Integer,byte[]>();
        for(Map.Entry<Integer,ByteArrayOutputStream> field:fields.entrySet())result.put(field.getKey(),field.getValue().toByteArray());
        return result;
    }
}
