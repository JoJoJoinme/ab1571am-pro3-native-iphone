package org.earbud.repro.fota;
import java.util.*;
public final class StagedHashProof {
 private StagedHashProof(){}
 public static byte[] query(int role){if(role<0||role>1)throw new IllegalArgumentException("Invalid ear role");return new byte[]{0x15,0x5a,0x0c,0,0x31,4,0,(byte)role,0,0x10,0x4b,0,0,(byte)0xf0,0x23,0};}
 static long u32(byte[] b,int i){return (b[i]&255L)|((b[i+1]&255L)<<8)|((b[i+2]&255L)<<16)|((b[i+3]&255L)<<24);}
 public static String validate(byte[] r,int role,String expected){
  if(role<0||role>1||r.length!=49||(r[0]!=5&&r[0]!=0x15)||r[1]!=0x5d||r[2]!=45||r[3]!=0||r[4]!=0x31||r[5]!=4||r[6]!=0||r[7]!=0||r[8]!=(byte)role||u32(r,9)!=0x4b1000L||u32(r,13)!=2355200L)throw new IllegalArgumentException("Mismatched hash response scope");
  StringBuilder h=new StringBuilder();for(int i=17;i<49;i++)h.append(String.format(Locale.ROOT,"%02x",r[i]&255));String actual=h.toString();if(!actual.equals(expected))throw new IllegalArgumentException("Staged data hash mismatch");return actual;
 }

 public static byte[] activeQuery(int role){if(role<0||role>1)throw new IllegalArgumentException("Invalid ear role");return new byte[]{0x15,0x5a,0x0c,0,0x31,4,0,(byte)role,0,(byte)0xa0,0x1c,0,0,(byte)0xe0,0x23,0};}
 public static String activeValidate(byte[] r,int role){
  if(role<0||role>1||r.length!=49||(r[0]!=5&&r[0]!=0x15)||r[1]!=0x5d||r[2]!=45||r[3]!=0||r[4]!=0x31||r[5]!=4||r[6]!=0||r[7]!=0||r[8]!=(byte)role||u32(r,9)!=0x1ca000L||u32(r,13)!=2351104L)throw new IllegalArgumentException("Mismatched active MCU hash scope");
  StringBuilder h=new StringBuilder();for(int i=17;i<49;i++)h.append(String.format(Locale.ROOT,"%02x",r[i]&255));return h.toString();
 }
}
