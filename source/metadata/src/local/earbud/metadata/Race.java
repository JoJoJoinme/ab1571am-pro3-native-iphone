package org.earbud.repro.metadata;
import java.util.*;
public final class Race {
 private Race(){}
 static byte[] packet(int command, byte[] payload){int len=payload.length+2;byte[] b=new byte[len+4];b[0]=5;b[1]=0x5a;b[2]=(byte)len;b[3]=(byte)(len>>8);b[4]=(byte)command;b[5]=(byte)(command>>8);System.arraycopy(payload,0,b,6,payload.length);return b;}
 public static byte[] query(int command, int key){
  if(command==0x0301||command==0x1e08||command==0x0d00)return packet(command,new byte[0]);
  if(command==0x1c00)return packet(command,new byte[]{0});
  if(command==0x0a00&&(key==0xfc04||key==0xfb03||key==0xfb00))return packet(command,new byte[]{(byte)key,(byte)(key>>8),(byte)0xe8,3});
  if(command==0x1680&&key==0x1423b29c)return packet(command,new byte[]{0,0,(byte)key,(byte)(key>>8),(byte)(key>>16),(byte)(key>>24)});
  throw new IllegalArgumentException("Only reviewed metadata and three NVKEY reads are allowed");
 }
 public static byte[] flashRead(int address,int size){
  if(address<0x08000000||address%256!=0||!Arrays.asList(256,512,1024,2048,4096).contains(size)||((long)address+size)>0x08800000L)throw new IllegalArgumentException("Flash read outside observed 8 MiB map");
  return packet(0x0403,new byte[]{0,(byte)(size/256),(byte)address,(byte)(address>>8),(byte)(address>>16),(byte)(address>>24)});
 }
 public static byte[] activeHash(int role){if(role<0||role>1)throw new IllegalArgumentException("Invalid role");return packet(0x0431,new byte[]{0,(byte)role,0,(byte)0xa0,0x1c,0,0,(byte)0xe0,0x23,0});}
 public static byte[] flashValue(byte[] f,int address,int size){
  flashRead(address,size);if(!reply(f,0x0403)||f.length!=size+14||f[6]!=0||f[7]!=0)throw new IllegalArgumentException("Flash reply rejected");
  int actual=(f[10]&255)|((f[11]&255)<<8)|((f[12]&255)<<16)|((f[13]&255)<<24);if(actual!=address)throw new IllegalArgumentException("Flash reply address mismatch");return Arrays.copyOfRange(f,14,f.length);
 }
 public static byte[] peer(byte[] destination,byte[] inner){
  if(destination==null||destination.length!=2||destination[0]!=5)throw new IllegalArgumentException("No discovered peer");
  int c=command(inner);if(c!=0x0301&&c!=0x1e08&&c!=0x0a00&&c!=0x0403&&c!=0x1c00&&c!=0x1680)throw new IllegalArgumentException("Disallowed relay command");
  int k=c==0x0a00&&inner.length==10?((inner[6]&255)|((inner[7]&255)<<8)):0;
  if(c==0x1680){if(inner.length!=12)throw new IllegalArgumentException("Invalid cache read packet");k=(inner[8]&255)|((inner[9]&255)<<8)|((inner[10]&255)<<16)|((inner[11]&255)<<24);}
  byte[] canonical;if(c==0x0403){if(inner.length!=12)throw new IllegalArgumentException("Invalid flash request");int address=(inner[8]&255)|((inner[9]&255)<<8)|((inner[10]&255)<<16)|((inner[11]&255)<<24);canonical=flashRead(address,256);}else canonical=query(c,k);
  if(!Arrays.equals(inner,canonical))throw new IllegalArgumentException("Noncanonical read packet");
  byte[] p=new byte[2+inner.length];System.arraycopy(destination,0,p,0,2);System.arraycopy(inner,0,p,2,inner.length);return packet(0x0d01,p);
 }
 public static int command(byte[] f){if(f.length<6)throw new IllegalArgumentException("Short frame");return (f[4]&255)|((f[5]&255)<<8);}
 public static boolean reply(byte[] f,int cmd){return f.length>=6&&f[0]==5&&((f[1]&255)==0x5b||(f[1]&255)==0x5d)&&((f[2]&255)|((f[3]&255)<<8))+4==f.length&&command(f)==cmd;}
 public static byte[] unwrap(byte[] f,byte[] dest){if(!reply(f,0x0d01)||(f[1]&255)!=0x5d||f.length<14||f[6]!=dest[0]||f[7]!=dest[1])return null;byte[] in=Arrays.copyOfRange(f,8,f.length);return in[1]==0x5b&&reply(in,command(in))?in:null;}
 public static byte[] discoverPeer(byte[] f){if(!reply(f,0x0d00)||(f.length-6)%2!=0)throw new IllegalArgumentException("Invalid destination layout");byte[] dst=null;for(int i=6;i<f.length;i+=2)if(f[i]==5){if(dst!=null)throw new IllegalArgumentException("Ambiguous peer");dst=Arrays.copyOfRange(f,i,i+2);}if(dst==null)throw new IllegalArgumentException("Peer unavailable");return dst;}
 public static byte[] nv(byte[] f){if(!reply(f,0x0a00)||f.length<8)throw new IllegalArgumentException("Invalid NV reply");int len=(f[6]&255)|((f[7]&255)<<8);if(len>1000||f.length!=len+8)throw new IllegalArgumentException("Invalid NV length");return Arrays.copyOfRange(f,8,f.length);}
 public static byte[] infoCache(byte[] f){if(!reply(f,0x1680)||f[1]!=0x5b||f.length!=17||f[6]!=0||!Arrays.equals(Arrays.copyOfRange(f,9,13),new byte[]{(byte)0x9c,(byte)0xb2,0x23,0x14}))throw new IllegalArgumentException("Cache reply address, status or length mismatch");return Arrays.copyOfRange(f,13,17);}
 public static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x ",v&255));return s.toString().trim();}
 public static class Parser {
  byte[] pending=new byte[0];
  public List<byte[]> feed(byte[] bytes){if(pending.length+bytes.length>16384)throw new IllegalArgumentException("Buffer overflow");byte[] all=Arrays.copyOf(pending,pending.length+bytes.length);System.arraycopy(bytes,0,all,pending.length,bytes.length);int i=0;List<byte[]> result=new ArrayList<>();while(all.length-i>=6){if(all[i]!=5){i++;continue;}int n=(all[i+2]&255)|((all[i+3]&255)<<8);if(n<2||n>8192)throw new IllegalArgumentException("Invalid frame length");if(all.length-i<n+4)break;result.add(Arrays.copyOfRange(all,i,i+n+4));i+=n+4;}pending=Arrays.copyOfRange(all,i,all.length);return result;}
 }
}
