import org.earbud.repro.fota.StagedHashProof;
import java.util.*;
public class StagedHashProofTest {
 static int count=0;static void ok(boolean b){count++;if(!b)throw new AssertionError("check "+count);}static void reject(Runnable r){boolean bad=false;try{r.run();}catch(IllegalArgumentException e){bad=true;}ok(bad);}
 public static void main(String[] a){
  byte[] f=new byte[49];byte[] prefix={0x15,0x5d,45,0,0x31,4,0,0,1,0,0x10,0x4b,0,0,(byte)0xf0,0x23,0};System.arraycopy(prefix,0,f,0,prefix.length);Arrays.fill(f,17,49,(byte)0xaa);String expected="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  ok(StagedHashProof.validate(f,1,expected).equals(expected));reject(()->StagedHashProof.validate(f,0,expected));reject(()->StagedHashProof.validate(f,1,expected.substring(2)));
  for(int index:new int[]{0,1,2,3,4,5,6,7,9,13,17}){byte[] bad=f.clone();bad[index]^=1;reject(()->StagedHashProof.validate(bad,1,expected));}
  reject(()->StagedHashProof.validate(Arrays.copyOf(f,48),1,expected));reject(()->StagedHashProof.query(2));
  byte[] q=StagedHashProof.query(1);ok(q.length==16&&q[0]==0x15&&q[7]==1&&q[14]==0x23);byte[] active=f.clone();byte[] scope={0,(byte)0xa0,0x1c,0,0,(byte)0xe0,0x23,0};System.arraycopy(scope,0,active,9,8);ok(StagedHashProof.activeValidate(active,1).equals(expected));reject(()->StagedHashProof.activeValidate(active,0));reject(()->StagedHashProof.activeValidate(f,1));reject(()->StagedHashProof.activeValidate(Arrays.copyOf(active,48),1));byte[] aq=StagedHashProof.activeQuery(0);ok(aq.length==16&&aq[9]==(byte)0xa0&&aq[10]==0x1c);reject(()->StagedHashProof.activeQuery(2));System.out.println(count+" staged and active hash proof checks passed");
 }
}
