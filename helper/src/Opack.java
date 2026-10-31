import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded OPACK subset for Companion. Protocol reference: pyatv (MIT). */
public final class Opack {
    static final int MAX = 1024 * 1024;
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) out.put((String)pairs[i],pairs[i+1]);
        return out;
    }
    static void le(ByteArrayOutputStream out, long value, int n) {
        for(int i=0;i<n;i++) out.write((int)(value >>> (8*i)) & 255);
    }
    public static byte[] pack(Object value) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); encode(out,value,0);
        if(out.size()>MAX) throw new IOException("OPACK too large");
        return out.toByteArray();
    }
    static void encode(ByteArrayOutputStream out,Object v,int depth) throws IOException {
        if(depth>32) throw new IOException("OPACK depth");
        if(v==null) out.write(4);
        else if(v instanceof Boolean) out.write((Boolean)v?1:2);
        else if(v instanceof Float || v instanceof Double) {out.write(0x36);le(out,Double.doubleToLongBits(((Number)v).doubleValue()),8);}
        else if(v instanceof Number) {
            long n=((Number)v).longValue();
            if(n>=0 && n<40) out.write((int)n+8);
            else {int size=n>=0&&n<=255?1:n>=0&&n<=65535?2:n>=0&&n<=0xffffffffL?4:8;
                out.write(size==1?0x30:size==2?0x31:size==4?0x32:0x33);le(out,n,size);}
        } else if(v instanceof String || v instanceof byte[]) {
            boolean str=v instanceof String; byte[] bytes=str?((String)v).getBytes(StandardCharsets.UTF_8):(byte[])v;
            if(bytes.length>MAX) throw new IOException("OPACK value too large");
            int len=bytes.length;
            if(len<=32)out.write((str?0x40:0x70)+len);
            else {int size=len<=255?1:len<=65535?2:4;out.write(str?0x60+size:size==4?0x93:0x90+size);le(out,len,size);}
            out.write(bytes);
        } else if(v instanceof Map) {
            Map<?,?> m=(Map<?,?>)v;out.write(0xe0+Math.min(m.size(),15));
            for(Map.Entry<?,?> e:m.entrySet()){encode(out,e.getKey(),depth+1);encode(out,e.getValue(),depth+1);}
            if(m.size()>=15)out.write(3);
        } else if(v instanceof List) {
            List<?> l=(List<?>)v;out.write(0xd0+Math.min(l.size(),15));
            for(Object item:l)encode(out,item,depth+1);if(l.size()>=15)out.write(3);
        } else throw new IOException("OPACK unsupported type");
    }
    public static Object unpack(byte[] bytes) throws IOException {
        if(bytes.length>MAX)throw new IOException("OPACK size");
        Decoder d=new Decoder(bytes);Object out=d.read(0);
        if(d.pos!=bytes.length)throw new IOException("OPACK trailing bytes");return out;
    }
    static final class Decoder {
        final byte[] data;int pos;final List<Object> refs=new ArrayList<>();
        Decoder(byte[] data){this.data=data;}
        int take() throws IOException {if(pos>=data.length)throw new EOFException();return data[pos++]&255;}
        long number(int n)throws IOException{long x=0;for(int i=0;i<n;i++)x|=(long)take()<<(8*i);return x;}
        byte[] bytes(long n)throws IOException{if(n<0||n>data.length-pos)throw new EOFException();byte[] b=Arrays.copyOfRange(data,pos,pos+(int)n);pos+=(int)n;return b;}
        Object read(int depth)throws IOException{
            if(depth>32)throw new IOException("OPACK depth");int t=take();Object v;
            if(t==1)return true;if(t==2)return false;if(t==4)return null;
            if(t>=8&&t<=0x2f)return (long)t-8;
            if(t>=0xa0&&t<=0xc4){long idx=t<=0xc0?t-0xa0:number(t-0xc0);if(idx>=refs.size()||idx<0)throw new IOException("OPACK reference");return refs.get((int)idx);}
            if(t>=0xd0&&t<=0xef){
                boolean dict=t>=0xe0;int count=t&15;Map<String,Object> m=new LinkedHashMap<>();List<Object> l=new ArrayList<>();
                for(int i=0;count==15||i<count;i++){
                    if(count==15&&pos<data.length&&(data[pos]&255)==3){pos++;break;}
                    Object item=read(depth+1);if(dict){if(!(item instanceof String))throw new IOException("OPACK key");m.put((String)item,read(depth+1));}else l.add(item);
                }return dict?m:l;
            }
            if(t==5)v=bytes(16);
            else if(t==6)v=number(8);
            else if(t>=0x30&&t<=0x33)v=number(1<<(t-0x30));
            else if(t==0x35)v=(double)Float.intBitsToFloat((int)number(4));
            else if(t==0x36)v=Double.longBitsToDouble(number(8));
            else if(t>=0x40&&t<=0x64)v=new String(bytes(t<=0x60?t-0x40:number(t-0x60)),StandardCharsets.UTF_8);
            else if(t>=0x70&&t<=0x94)v=bytes(t<=0x90?t-0x70:number(1<<(t-0x91)));
            else throw new IOException("OPACK tag");
            boolean found=false;for(Object old:refs)if(old instanceof byte[]&&v instanceof byte[]?Arrays.equals((byte[])old,(byte[])v):Objects.equals(old,v)){found=true;break;}
            if(!found)refs.add(v);return v;
        }
    }
}
