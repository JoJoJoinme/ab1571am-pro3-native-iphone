import org.earbud.repro.readonly.Race;
import java.util.*;
public class RaceTest {
 static int n=0;static void ok(boolean b){n++;if(!b)throw new AssertionError("check "+n);}static void reject(Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException e){rejected=true;}ok(rejected);}
 public static void main(String[] args){
 ok(Race.hex(Race.query(0x301,0)).equals("05 5a 02 00 01 03"));
 ok(Race.hex(Race.query(0xa00,0xfc04)).equals("05 5a 06 00 00 0a 04 fc e8 03"));
 reject(()->Race.query(0xa01,0xfb00));reject(()->Race.query(0x1681,0));reject(()->Race.query(0xa00,0x1234));
 byte[] reply={5,0x5b,5,0,0,10,1,0,1};Race.Parser p=new Race.Parser();ok(p.feed(Arrays.copyOf(reply,4)).isEmpty());ok(Arrays.equals(p.feed(Arrays.copyOfRange(reply,4,reply.length)).get(0),reply));
 byte[] twice=new byte[reply.length*2];System.arraycopy(reply,0,twice,0,reply.length);System.arraycopy(reply,0,twice,reply.length,reply.length);ok(new Race.Parser().feed(twice).size()==2);ok(Race.nv(reply)[0]==1);reject(()->Race.nv(Arrays.copyOf(reply,8)));ok(!Race.reply(Race.query(0x301,0),0x301));
 reject(()->new Race.Parser().feed(new byte[]{5,0x5b,1,0,0,0}));
 byte[] dests={5,0x5b,4,0,0,13,5,6};ok(Arrays.equals(Race.discoverPeer(dests),new byte[]{5,6}));reject(()->Race.discoverPeer(new byte[]{5,0x5b,6,0,0,13,5,6,5,7}));
 byte[] relay=Race.peer(new byte[]{5,6},Race.query(0x301,0));ok(Race.command(relay)==0xd01);byte[] bad=Race.query(0xa00,0xfb00);bad[4]=1;reject(()->Race.peer(new byte[]{5,6},bad));
 byte[] wrapped={5,0x5d,13,0,1,13,5,6,5,0x5b,5,0,0,10,1,0,1};ok(Arrays.equals(Race.unwrap(wrapped,new byte[]{5,6}),reply));ok(Race.unwrap(wrapped,new byte[]{5,7})==null);wrapped[1]=0x5b;ok(Race.unwrap(wrapped,new byte[]{5,6})==null);
 ok(Race.hex(Race.flashRead(0x08000000,256)).equals("05 5a 08 00 03 04 00 01 00 00 00 08"));
 reject(()->Race.flashRead(0x07ffff00,256));reject(()->Race.flashRead(0x087fff00,512));reject(()->Race.flashRead(0x08000001,256));reject(()->Race.flashRead(0x08000000,0));
 byte[] page=new byte[270];page[0]=5;page[1]=0x5b;page[2]=10;page[3]=1;page[4]=3;page[5]=4;page[13]=8;ok(Race.flashValue(page,0x08000000,256).length==256);reject(()->Race.flashValue(page,0x08000100,256));page[6]=1;reject(()->Race.flashValue(page,0x08000000,256));
 ok(Race.hex(Race.peer(new byte[]{5,6},Race.flashRead(0x08000000,256))).equals("05 5a 10 00 01 0d 05 06 05 5a 08 00 03 04 00 01 00 00 00 08"));
 byte[] erase=Race.flashRead(0x08000000,256);erase[4]=4;reject(()->Race.peer(new byte[]{5,6},erase));
 reject(()->Race.peer(new byte[]{5,6},Race.flashRead(0x08000000,512)));
 ok(Race.hex(Race.query(0x1c00,0)).equals("05 5a 03 00 00 1c 00"));
 ok(Race.hex(Race.peer(new byte[]{5,6},Race.query(0x1c00,0))).equals("05 5a 0b 00 01 0d 05 06 05 5a 03 00 00 1c 00"));
 for(int denied:new int[]{0x0402,0x0404,0x1c01,0x1c02,0x1c03,0x1c06,0x1c08,0x1c0a}){final int c=denied;reject(()->Race.query(c,0));}
 ok(Race.hex(Race.activeHash(0)).equals("05 5a 0c 00 31 04 00 00 00 a0 1c 00 00 e0 23 00"));reject(()->Race.activeHash(2));
 ok(Race.hex(Race.query(0x1680,0x1423b29c)).equals("05 5a 08 00 80 16 00 00 9c b2 23 14"));
 reject(()->Race.query(0x1680,0x1423b29d));reject(()->Race.query(0x1681,0x1423b29c));
 byte[] cache={5,0x5b,13,0,(byte)0x80,0x16,0,0,0,(byte)0x9c,(byte)0xb2,0x23,0x14,1,0,0x41,0x42};
 ok(Race.infoCache(cache)[0]==1&&Race.infoCache(cache)[1]==0);byte[] invalidCache=cache.clone();invalidCache[9]++;reject(()->Race.infoCache(invalidCache));
 ok(Race.command(Race.peer(new byte[]{5,6},Race.query(0x1680,0x1423b29c)))==0x0d01);
 System.out.println(n+" read-only protocol checks passed");
 }
}
