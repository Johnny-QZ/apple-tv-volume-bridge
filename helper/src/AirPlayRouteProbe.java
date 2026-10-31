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

import com.dd.plist.*;
/** Read-only native MRP route adapter. Does not send volume or playback commands. */
public final class AirPlayRouteProbe implements Closeable {
 static final String[] INIT={"080f120d726f7574652d70726f62652d302000aa052430393246314437442d353934392d344634352d423634432d424244443336383730393246a2018c010a2438354538434236312d384539412d344442462d423038432d443641313533423934374143120f545620566f6c756d6520526f7574651a066950686f6e65220531384738322a12636f6d2e6170706c652e545652656d6f746532063334342e32383801406c480150016211636f6d2e6170706c652e54564d75736963680170017801880102a80101b00101","0826120d726f7574652d70726f62652d312000aa052434394531453232392d373534392d343431412d413234412d374430413435343245424645d202020802","0810120d726f7574652d70726f62652d322000aa052431323741344145322d444542382d343939382d393444392d334246423231453734463944aa010a08001000180120002801"};
 HapSocket control,event,data;byte[] secret;int sequence=2;
 String host,uri;
 AirPlayRouteProbe(String host,int port,String credentials)throws Exception{
  this.host=host;String[] fields=credentials.trim().split(":");if(fields.length!=4)throw new IOException("Credential format");
  byte[] ltpk=CompanionClient.hex(fields[0]),ltsk=CompanionClient.hex(fields[1]),atvId=CompanionClient.hex(fields[2]),clientId=CompanionClient.hex(fields[3]);
  if(ltpk.length!=32||ltsk.length!=32||atvId.length==0||clientId.length==0)throw new IOException("Credential lengths");
  control=new HapSocket(host,port);uri="rtsp://"+control.socket.getLocalAddress().getHostAddress()+"/12345678";
  try(SocketDeadline deadline=new SocketDeadline(control.socket,15000)){
            X25519PrivateKeyParameters ephemeral=new X25519PrivateKeyParameters(new SecureRandom());
            byte[] publicKey=ephemeral.generatePublicKey().getEncoded();
            Map<Integer,byte[]> reply=CompanionClient.readTlv(control.http("POST","/pair-verify","HTTP/1.1","application/octet-stream",CompanionClient.tlv(6,new byte[]{1},3,publicKey),0));
            byte[] peerPublic=CompanionClient.required(reply,3), shared=new byte[32];
            if(peerPublic.length!=32)throw new IOException("Peer public key length");
            ephemeral.generateSecret(new X25519PublicKeyParameters(peerPublic,0),shared,0);
            byte[] verifyKey=CompanionClient.hkdf(shared,"Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info");
            Map<Integer,byte[]> peer=CompanionClient.readTlv(CompanionClient.crypt(false,verifyKey,CompanionClient.nonce("PV-Msg02"),null,CompanionClient.required(reply,5)));
            if(!Arrays.equals(atvId,CompanionClient.required(peer,1)))throw new IOException("Peer identity mismatch");
            Ed25519Signer verifier=new Ed25519Signer();verifier.init(false,new Ed25519PublicKeyParameters(ltpk,0));
            byte[] signed=CompanionClient.join(peerPublic,atvId,publicKey);verifier.update(signed,0,signed.length);
            if(!verifier.verifySignature(CompanionClient.required(peer,10)))throw new IOException("Peer signature mismatch");
            Ed25519Signer signer=new Ed25519Signer();signer.init(true,new Ed25519PrivateKeyParameters(ltsk,0));
            signed=CompanionClient.join(publicKey,clientId,peerPublic);signer.update(signed,0,signed.length);
            byte[] encrypted=CompanionClient.crypt(true,verifyKey,CompanionClient.nonce("PV-Msg03"),null,CompanionClient.tlv(1,clientId,10,signer.generateSignature()));
            Map<Integer,byte[]> finish=CompanionClient.readTlv(control.http("POST","/pair-verify","HTTP/1.1","application/octet-stream",CompanionClient.tlv(6,new byte[]{3},5,encrypted),1));
            if(!Arrays.equals(CompanionClient.required(finish,6),new byte[]{4}))throw new IOException("Pair verify sequence");

   secret=shared;control.enable(shared,"Control-Salt","Control-Write-Encryption-Key","Control-Read-Encryption-Key");
   Map<?,?> setup=setup(Opack.map("isRemoteControlOnly",true,"osName","iPhone OS","sourceVersion","550.10","timingProtocol","None","model","iPhone","deviceID","02:00:00:00:00:01","osVersion","17.0","osBuildVersion","21A329","macAddress","02:00:00:00:00:01","sessionUUID",UUID.randomUUID().toString(),"name","TV Volume Route"));
   event=new HapSocket(host,((Number)setup.get("eventPort")).intValue());event.enable(shared,"Events-Salt","Events-Read-Encryption-Key","Events-Write-Encryption-Key");
   control.http("RECORD",uri,"RTSP/1.0","application/octet-stream",new byte[0],sequence++);
   long seed=new SecureRandom().nextInt()&0xffffffffL;
   setup=setup(Opack.map("streams",Arrays.asList(Opack.map("controlType",2,"channelID",UUID.randomUUID().toString(),"seed",seed,"clientUUID",UUID.randomUUID().toString(),"type",130,"wantsDedicatedSocket",true,"clientTypeUUID","1910A70F-DBC0-4242-AF95-115DB30604E1"))));
   Object streams=setup.get("streams");Object stream=streams instanceof Object[]?((Object[])streams)[0]:((List<?>)streams).get(0);
   data=new HapSocket(host,((Number)((Map<?,?>)stream).get("dataPort")).intValue());data.enable(shared,"DataStream-Salt"+seed,"DataStream-Output-Encryption-Key","DataStream-Input-Encryption-Key");deadline.check();
  }catch(Exception e){close();throw e;}
 }
 static byte[] plist(Object v)throws Exception{return BinaryPropertyListWriter.writeToArray(NSObject.fromJavaObject(v));}
 Map<?,?> setup(Map<String,Object> value)throws Exception{return (Map<?,?>)BinaryPropertyListParser.parse(control.http("SETUP",uri,"RTSP/1.0","application/x-apple-binary-plist",plist(value),sequence++)).toJavaObject();}

 static final class PB {
  final byte[] bytes;int pos;PB(byte[] b){bytes=b;}
  long var()throws IOException{long v=0;for(int i=0;i<10;i++){if(pos>=bytes.length)throw new EOFException();int b=bytes[pos++]&255;v|=(long)(b&127)<<(7*i);if((b&128)==0)return v;}throw new IOException("Protobuf varint");}
  Map<Integer,List<Object>> fields()throws Exception{Map<Integer,List<Object>> m=new HashMap<>();while(pos<bytes.length){long tag=var();int key=(int)(tag>>>3),wire=(int)(tag&7);Object v;if(wire==0)v=var();else if(wire==2){long n=var();if(n<0||n>bytes.length-pos)throw new IOException("Protobuf bounds");v=Arrays.copyOfRange(bytes,pos,pos+(int)n);pos+=(int)n;}else if(wire==1||wire==5){int n=wire==1?8:4;if(pos+n>bytes.length)throw new EOFException();v=Arrays.copyOfRange(bytes,pos,pos+n);pos+=n;}else throw new IOException("Protobuf wire");if(!m.containsKey(key))m.put(key,new ArrayList<Object>());m.get(key).add(v);}return m;}
 }
 static byte[] var(long n){ByteArrayOutputStream out=new ByteArrayOutputStream();while(n>127){out.write((int)n&127|128);n>>>=7;}out.write((int)n);return out.toByteArray();}
 void sendPB(byte[] pb)throws Exception{byte[] payload=plist(Opack.map("params",Opack.map("data",CompanionClient.join(var(pb.length),pb))));frame("sync","comm",1,payload);}
 void frame(String type,String command,long seq,byte[] payload)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(bytes);d.writeInt(32+payload.length);byte[] t=new byte[12],c=new byte[4];System.arraycopy(type.getBytes("UTF-8"),0,t,0,type.length());System.arraycopy(command.getBytes("UTF-8"),0,c,0,command.length());d.write(t);d.write(c);d.writeLong(seq);d.writeInt(0);d.write(payload);data.write(bytes.toByteArray());}
 static Object first(Map<Integer,List<Object>> fields,int key){return fields.containsKey(key)?fields.get(key).get(0):null;}
 String ownUid=null,routeIdentity=null;boolean deviceSeen=false;int capability=-1;long routeRevision,routeReceivedNs;List<String> names=new ArrayList<>();
 static String outputIdentity(Map<Integer,List<Object>> fields)throws Exception{
  // Include all advertised identifiers, including uniqueIdentifier (1) and deviceUID (19).
  // Length prefixes avoid ambiguous concatenations; sorting outputs ignores order-only updates.
  StringBuilder value=new StringBuilder();
  for(int field:new int[]{1,2,19,26,35}){Object raw=first(fields,field);String part=raw instanceof byte[]?new String((byte[])raw,"UTF-8"):"";if(field==2)part=AudioRoutePolicy.normalizeName(part);value.append(field).append(':').append(part.length()).append(':').append(part);}
  return value.toString();
 }
 void consume(byte[] message)throws Exception{
  Map<Integer,List<Object>> fields=new PB(message).fields();Object info=first(fields,20);
  if(info instanceof byte[]){
   Map<Integer,List<Object>> dev=new PB((byte[])info).fields();Object uid=first(dev,35);
   if(!(uid instanceof byte[])||((byte[])uid).length==0)uid=first(dev,19);
   String nextUid=uid instanceof byte[]&&((byte[])uid).length>0?new String((byte[])uid,"UTF-8"):null;
   if(!Objects.equals(ownUid,nextUid))capability=-1;
   ownUid=nextUid;names.clear();List<String> identities=new ArrayList<>();boolean ambiguous=false;
   // pyatv's output model includes the host only when it is an audible group leader.
   // Device identity alone is not proof that the host is an active speaker output.
   if(Long.valueOf(1).equals(first(dev,29))&&!Long.valueOf(1).equals(first(dev,24))){
    Object name=first(dev,2);if(name instanceof byte[]){names.add(new String((byte[])name,"UTF-8"));identities.add(outputIdentity(dev));}else ambiguous=true;
   }
   if(dev.containsKey(28))for(Object child:dev.get(28)){
    if(!(child instanceof byte[])){ambiguous=true;continue;}
    Map<Integer,List<Object>> childFields=new PB((byte[])child).fields();Object name=first(childFields,2);
    if(name instanceof byte[]){names.add(new String((byte[])name,"UTF-8"));identities.add(outputIdentity(childFields));}else ambiguous=true;
    if(childFields.containsKey(28)||childFields.containsKey(33)||childFields.containsKey(44))ambiguous=true;
   }
   if(dev.containsKey(33)||dev.containsKey(44)||ambiguous)names.clear();
   Collections.sort(identities);
   routeIdentity=names.isEmpty()?null:outputIdentity(dev)+identities.toString();
   deviceSeen=true;routeRevision++;routeReceivedNs=System.nanoTime();
  }
  Object changed=first(fields,68);if(changed instanceof byte[]){Map<Integer,List<Object>> update=new PB((byte[])changed).fields();Object uid=first(update,4),nested=first(update,1);if(uid instanceof byte[]&&ownUid!=null&&ownUid.equals(new String((byte[])uid,"UTF-8"))&&nested instanceof byte[]){Object v=first(new PB((byte[])nested).fields(),2);if(v instanceof Number)capability=((Number)v).intValue();}}
  Object cap=first(fields,22);if(cap instanceof byte[]){Object v=first(new PB((byte[])cap).fields(),2);if(v instanceof Number)capability=((Number)v).intValue();}
 }
 void receiveFrame()throws Exception{receiveFrame(data.read());}
 void receiveFrame(int firstByte)throws Exception{
  try(SocketDeadline deadline=new SocketDeadline(data.socket,4000)){
  byte[] header=CompanionClient.join(new byte[]{(byte)firstByte},data.readBytes(31));DataInputStream h=new DataInputStream(new ByteArrayInputStream(header));int len=h.readInt();if(len<32||len>1048576)throw new IOException("Data frame bound");byte[] type=new byte[12];h.readFully(type);h.readInt();long seq=h.readLong();h.readInt();byte[] body=data.readBytes(len-32);
  if(new String(type,"UTF-8").startsWith("sync"))frame("rply","",seq,new byte[0]);
  if(body.length==0)return;Map<?,?> root=(Map<?,?>)BinaryPropertyListParser.parse(body).toJavaObject();Object params=root.get("params");if(!(params instanceof Map))return;Object raw=((Map<?,?>)params).get("data");if(!(raw instanceof byte[]))return;
  PB stream=new PB((byte[])raw);while(stream.pos<stream.bytes.length){if((stream.bytes[stream.pos]&255)==8){consume(Arrays.copyOfRange(stream.bytes,stream.pos,stream.bytes.length));break;}long n=stream.var();if(n<0||n>stream.bytes.length-stream.pos)throw new IOException("Message length");consume(Arrays.copyOfRange(stream.bytes,stream.pos,stream.pos+(int)n));stream.pos+=(int)n;}
  deadline.check();}
 }
 void initialize()throws Exception{
  try(SocketDeadline deadline=new SocketDeadline(data.socket,8000)){
  requestRouteSnapshot();long until=System.nanoTime()+4000000000L;while(!deviceSeen&&System.nanoTime()<until)receiveFrame();
  sendPB(CompanionClient.hex(INIT[1]));sendPB(CompanionClient.hex(INIT[2]));
  until=System.nanoTime()+4000000000L;while(capability<0&&System.nanoTime()<until)receiveFrame();
  deadline.check();}
 }
 void requestRouteSnapshot()throws Exception{
  Map<Integer,List<Object>> fields=new PB(CompanionClient.hex(INIT[0])).fields();
  fields.put(2,Arrays.<Object>asList(UUID.randomUUID().toString().getBytes("UTF-8")));
  fields.put(85,Arrays.<Object>asList(UUID.randomUUID().toString().getBytes("UTF-8")));
  ByteArrayOutputStream message=new ByteArrayOutputStream();
  for(Map.Entry<Integer,List<Object>> field:fields.entrySet())for(Object value:field.getValue()){
   if(value instanceof Number){message.write(var(((long)field.getKey()<<3)));message.write(var(((Number)value).longValue()));}
   else{byte[] bytes=(byte[])value;message.write(var(((long)field.getKey()<<3)|2));message.write(var(bytes.length));message.write(bytes);}
  }
  sendPB(message.toByteArray());
 }
 void feedback()throws Exception{control.http("POST","/feedback","RTSP/1.0","application/octet-stream",new byte[0],sequence++);}
 void startEvents(){final HapSocket channel=event;Thread t=new Thread(new Runnable(){public void run(){try{channel.socket.setSoTimeout(0);while(!channel.socket.isClosed()){
  int first=channel.read();
  try(SocketDeadline deadline=new SocketDeadline(channel.socket,5000)){
  String request=channel.line(first);if(request.length()==0)continue;String cseq="0";int length=0,count=0;
  for(String line;(line=channel.line()).length()>0;){if(++count>100)throw new IOException("Event headers");String low=line.toLowerCase(Locale.ROOT);if(low.startsWith("cseq:"))cseq=line.substring(5).trim();if(low.startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());}
  if(!cseq.matches("[0-9]{1,10}"))throw new IOException("Event sequence");channel.readBytes(length);channel.write(("RTSP/1.0 200 OK\r\nCSeq: "+cseq+"\r\nContent-Length: 0\r\nAudio-Latency: 0\r\n\r\n").getBytes("UTF-8"));deadline.check();}
 } }catch(Exception ignored){}}},"RouteEvents");t.setDaemon(true);t.start();}
 public void close(){if(data!=null)data.close();if(event!=null)event.close();if(control!=null)control.close();if(secret!=null)Arrays.fill(secret,(byte)0);}
}
