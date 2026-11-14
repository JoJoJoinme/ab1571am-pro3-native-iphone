package org.earbud.repro.metadata;
import java.util.*;

public final class MetadataProtocol {
 private MetadataProtocol(){}
 public static byte[] enableNv(){return Race.packet(0x0a01,new byte[]{0,(byte)0xfb,1});}
 public static byte[] enabledWord(byte[] old){
  if(old==null||old.length!=4||old[0]!=1||(old[1]!=0&&old[1]!=1))throw new IllegalArgumentException("Metadata not loaded or unexpected flag");
  byte[] updated=old.clone();updated[1]=1;return updated;
 }
 public static byte[] enableCache(byte[] old){
  byte[] word=enabledWord(old);byte[] p=new byte[]{0,0,(byte)0x9c,(byte)0xb2,0x23,0x14,word[0],word[1],word[2],word[3]};
  return Race.packet(0x1681,p);
 }
 public static byte[] relay(byte[] destination,byte[] inner){
  if(destination==null||destination.length!=2||destination[0]!=5)throw new IllegalArgumentException("Invalid peer");
  int c=Race.command(inner);
  if(c!=0x0a01&&c!=0x1681)return Race.peer(destination,inner);
  boolean valid=c==0x0a01?Arrays.equals(inner,enableNv()):inner.length==16&&inner[0]==5&&inner[1]==0x5a&&inner[2]==12&&inner[3]==0&&Arrays.equals(Arrays.copyOfRange(inner,6,12),new byte[]{0,0,(byte)0x9c,(byte)0xb2,0x23,0x14})&&inner[12]==1&&inner[13]==1;
  if(!valid)throw new IllegalArgumentException("Write outside reviewed metadata scope");
  byte[] p=new byte[2+inner.length];System.arraycopy(destination,0,p,0,2);System.arraycopy(inner,0,p,2,inner.length);return Race.packet(0x0d01,p);
 }
 public static void ack(byte[] f,int command){if(!Race.reply(f,command)||f[1]!=0x5b||f.length!=7||f[6]!=0)throw new IllegalArgumentException("Write not acknowledged successfully");}
}
