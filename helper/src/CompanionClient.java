import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.modes.ChaCha20Poly1305;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/** Paired, volume-only Companion transport. No discovery, pairing or power commands. */
public final class CompanionClient implements Closeable {
    final Socket socket=new Socket();
    DataInputStream in;OutputStream out;
    byte[] encryptKey,decryptKey;long sent,received;long xid=100;
    public CompanionClient(String host,int port,String credentials)throws Exception {
        try {
            String[] fields=credentials.trim().split(":");if(fields.length!=4)throw new IOException("Credential format");
            byte[] ltpk=hex(fields[0]),ltsk=hex(fields[1]),atvId=hex(fields[2]),clientId=hex(fields[3]);
            if(ltpk.length!=32||ltsk.length!=32||atvId.length==0||clientId.length==0)throw new IOException("Credential lengths");
            socket.connect(new InetSocketAddress(host,port),3000);socket.setSoTimeout(4000);socket.setTcpNoDelay(true);
            in=new DataInputStream(socket.getInputStream());out=socket.getOutputStream();
            X25519PrivateKeyParameters ephemeral=new X25519PrivateKeyParameters(new SecureRandom());
            byte[] publicKey=ephemeral.generatePublicKey().getEncoded();
            send(5,Opack.map("_pd",tlv(6,new byte[]{1},3,publicKey),"_auTy",4));
            Map<Integer,byte[]> reply=pairing(receive(6));
            byte[] peerPublic=required(reply,3), shared=new byte[32];
            if(peerPublic.length!=32)throw new IOException("Peer public key length");
            ephemeral.generateSecret(new X25519PublicKeyParameters(peerPublic,0),shared,0);
            byte[] verifyKey=hkdf(shared,"Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info");
            Map<Integer,byte[]> peer=readTlv(crypt(false,verifyKey,nonce("PV-Msg02"),null,required(reply,5)));
            if(!Arrays.equals(atvId,required(peer,1)))throw new IOException("Peer identity mismatch");
            Ed25519Signer verifier=new Ed25519Signer();verifier.init(false,new Ed25519PublicKeyParameters(ltpk,0));
            byte[] signed=join(peerPublic,atvId,publicKey);verifier.update(signed,0,signed.length);
            if(!verifier.verifySignature(required(peer,10)))throw new IOException("Peer signature mismatch");
            Ed25519Signer signer=new Ed25519Signer();signer.init(true,new Ed25519PrivateKeyParameters(ltsk,0));
            signed=join(publicKey,clientId,peerPublic);signer.update(signed,0,signed.length);
            byte[] encrypted=crypt(true,verifyKey,nonce("PV-Msg03"),null,tlv(1,clientId,10,signer.generateSignature()));
            send(6,Opack.map("_pd",tlv(6,new byte[]{3},5,encrypted)));
            Map<Integer,byte[]> finish=pairing(receive(6));
            if(!Arrays.equals(required(finish,6),new byte[]{4}))throw new IOException("Pair verify sequence");
            encryptKey=hkdf(shared,"","ClientEncrypt-main");decryptKey=hkdf(shared,"","ServerEncrypt-main");
            Arrays.fill(shared,(byte)0);Arrays.fill(ltsk,(byte)0);
            command("_systemInfo",Opack.map("_bf",0,"_cf",512,"_clFl",128,
                "_i",new String(clientId,StandardCharsets.UTF_8),"_idsID",clientId,
                "_pubID","02:00:00:00:00:01","_sf",256,"_sv","170.18","model","iPhone","name","TV Volume Bridge"));
            command("_sessionStart",Opack.map("_srvT","com.apple.tvremoteservices","_sid",new SecureRandom().nextInt()&0xffffffffL));
            command("TVRCSessionStart",Opack.map("ProtocolVersionKey","1.2"));
        } catch(Exception e){close();throw e;}
    }
    public synchronized double getVolume()throws Exception {
        Map<String,Object> reply=command("_mcc",Opack.map("_mcc",5));
        Object v=reply.get("_vol");if(!(v instanceof Number))throw new IOException("Volume unavailable");
        double value=((Number)v).doubleValue()*100;
        if(Double.isNaN(value)||Double.isInfinite(value)||value<0||value>100)throw new IOException("Invalid volume");
        return value;
    }
    public synchronized void setVolume(double value)throws Exception {
        if(Double.isNaN(value)||Double.isInfinite(value)||value<0||value>100)throw new IllegalArgumentException("Volume bounds");
        // One absolute write only. Caller reconciles ambiguous failure with a fresh connection/read.
        command("_mcc",Opack.map("_mcc",6,"_vol",value/100.0));
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> command(String name,Map<String,Object> content)throws Exception {
        long id=++xid;send(8,Opack.map("_i",name,"_t",2,"_x",id,"_c",content));
        long deadline=System.nanoTime()+4000000000L;
        for(int n=0;n<128;n++){
            long remaining=(deadline-System.nanoTime())/1000000;
            if(remaining<=0)throw new SocketTimeoutException();socket.setSoTimeout((int)remaining);
            Map<String,Object> reply=receive(8);
            if(reply.get("_x") instanceof Number && ((Number)reply.get("_x")).longValue()==id && Objects.equals(reply.get("_t"),3L)){
                if(reply.containsKey("_em"))throw new IOException("Companion rejected command");
                Object c=reply.get("_c");if(!(c instanceof Map))throw new IOException("Missing response content");return (Map<String,Object>)c;
            }
        }throw new IOException("Companion event limit");
    }
    void send(int type,Map<String,Object> payload)throws Exception {
        byte[] body=Opack.pack(payload);int len=body.length+(encryptKey==null?0:16);
        byte[] header={(byte)type,(byte)(len>>>16),(byte)(len>>>8),(byte)len};
        if(encryptKey!=null)body=crypt(true,encryptKey,counter(sent++),header,body);
        out.write(header);out.write(body);out.flush();
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> receive(int expectedType)throws Exception {
        byte[] header=new byte[4];in.readFully(header);int type=header[0]&255;
        int len=((header[1]&255)<<16)|((header[2]&255)<<8)|(header[3]&255);
        if(type!=expectedType||len<1||len>Opack.MAX)throw new IOException("Unexpected frame");
        byte[] body=new byte[len];in.readFully(body);
        if(decryptKey!=null)body=crypt(false,decryptKey,counter(received++),header,body);
        Object decoded=Opack.unpack(body);if(!(decoded instanceof Map))throw new IOException("Frame is not dictionary");return (Map<String,Object>)decoded;
    }
    static byte[] crypt(boolean encrypt,byte[] key,byte[] nonce,byte[] aad,byte[] input)throws Exception {
        ChaCha20Poly1305 cipher=new ChaCha20Poly1305();cipher.init(encrypt,new AEADParameters(new KeyParameter(key),128,nonce,aad));
        byte[] output=new byte[cipher.getOutputSize(input.length)];int n=cipher.processBytes(input,0,input.length,output,0);n+=cipher.doFinal(output,n);return Arrays.copyOf(output,n);
    }
    static byte[] hkdf(byte[] secret,String salt,String info){
        HKDFBytesGenerator gen=new HKDFBytesGenerator(new SHA512Digest());gen.init(new HKDFParameters(secret,salt.getBytes(StandardCharsets.UTF_8),info.getBytes(StandardCharsets.UTF_8)));
        byte[] key=new byte[32];gen.generateBytes(key,0,32);return key;
    }
    static byte[] nonce(String value){byte[] nonce=new byte[12],s=value.getBytes(StandardCharsets.UTF_8);System.arraycopy(s,0,nonce,12-s.length,s.length);return nonce;}
    static byte[] counter(long value){byte[] n=new byte[12];for(int i=0;i<8;i++)n[i]=(byte)(value>>>(8*i));return n;}
    static byte[] join(byte[]... parts)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] p:parts)out.write(p);return out.toByteArray();}
    static byte[] hex(String s)throws IOException{if(s.length()%2!=0)throw new IOException("Hex length");byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++){int h=Character.digit(s.charAt(i*2),16),l=Character.digit(s.charAt(i*2+1),16);if(h<0||l<0)throw new IOException("Hex character");b[i]=(byte)(h*16+l);}return b;}
    static byte[] tlv(Object... pairs)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<pairs.length;i+=2){byte[] b=(byte[])pairs[i+1];for(int p=0;p<b.length;p+=255){int n=Math.min(255,b.length-p);out.write((Integer)pairs[i]);out.write(n);out.write(b,p,n);}}return out.toByteArray();
    }
    static Map<Integer,byte[]> readTlv(byte[] data)throws IOException{
        Map<Integer,byte[]> m=new HashMap<>();for(int p=0;p<data.length;){if(p+2>data.length)throw new EOFException();int t=data[p++]&255,n=data[p++]&255;if(p+n>data.length)throw new EOFException();byte[] b=Arrays.copyOfRange(data,p,p+n);m.put(t,m.containsKey(t)?join(m.get(t),b):b);p+=n;}return m;
    }
    static byte[] required(Map<Integer,byte[]> m,int key)throws IOException{byte[] b=m.get(key);if(b==null)throw new IOException("Missing pairing field");return b;}
    static Map<Integer,byte[]> pairing(Map<String,Object> reply)throws IOException{Object pd=reply.get("_pd");if(!(pd instanceof byte[]))throw new IOException("Missing pairing data");Map<Integer,byte[]> m=readTlv((byte[])pd);if(m.containsKey(7))throw new IOException("Pair verify rejected");return m;}
    public void close(){try{socket.close();}catch(IOException ignored){}if(encryptKey!=null)Arrays.fill(encryptKey,(byte)0);if(decryptKey!=null)Arrays.fill(decryptKey,(byte)0);}
}
