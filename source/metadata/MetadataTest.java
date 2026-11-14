import org.earbud.repro.metadata.*;
import java.util.*;
public class MetadataTest {
 static int n=0;static void ok(boolean b){n++;if(!b)throw new AssertionError("check "+n);}static void reject(Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException e){rejected=true;}ok(rejected);}
 public static void main(String[] args){
  ok(Race.hex(MetadataProtocol.enableNv()).equals("05 5a 05 00 01 0a 00 fb 01"));
  for(int flag=0;flag<=1;flag++)for(int a=0;a<256;a++){byte[] old={1,(byte)flag,(byte)a,(byte)(255-a)};byte[] updated=MetadataProtocol.enabledWord(old);ok(updated[0]==old[0]&&updated[1]==1&&updated[2]==old[2]&&updated[3]==old[3]);ok(old[1]==flag);}
  reject(()->MetadataProtocol.enabledWord(new byte[]{0,0,1,2}));reject(()->MetadataProtocol.enabledWord(new byte[]{1,2,1,2}));reject(()->MetadataProtocol.enabledWord(new byte[]{1,0}));
  byte[] packet=MetadataProtocol.enableCache(new byte[]{1,0,0x41,0x42});ok(Race.hex(packet).equals("05 5a 0c 00 81 16 00 00 9c b2 23 14 01 01 41 42"));
  ok(Race.command(MetadataProtocol.relay(new byte[]{5,6},packet))==0xd01);byte[] wrong=packet.clone();wrong[8]++;reject(()->MetadataProtocol.relay(new byte[]{5,6},wrong));
  byte[] other=MetadataProtocol.enableNv();other[6]=3;reject(()->MetadataProtocol.relay(new byte[]{5,6},other));
  MetadataProtocol.ack(new byte[]{5,0x5b,3,0,1,10,0},0xa01);n++;
  reject(()->MetadataProtocol.ack(new byte[]{5,0x5b,3,0,1,10,1},0xa01));reject(()->MetadataProtocol.ack(new byte[]{5,0x5b,3,0,1,10,0},0x1681));
  System.out.println(n+" metadata protocol checks passed");
 }
}
