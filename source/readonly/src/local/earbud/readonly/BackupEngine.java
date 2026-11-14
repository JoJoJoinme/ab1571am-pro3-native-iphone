package org.earbud.repro.readonly;

import android.bluetooth.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;

/** Canonical read commands only. A file is finalized only after every page is validated. */
final class BackupEngine {
 interface Listener {void log(String s);void finished();}
 final BluetoothDevice device;final File dir;final Handler main;final Listener listener;
 final int start,length,window;final boolean peer;
 final Race.Parser parser=new Race.Parser();final ArrayDeque<byte[]> received=new ArrayDeque<>();
 final JSONObject report=new JSONObject();volatile boolean cancelled=false;volatile long deadline=0;
 BluetoothSocket socket;InputStream input;OutputStream output;byte[] destination;
 final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
 long started=0;int sent=0,completed=0;String cancelReason="";
 BackupEngine(BluetoothDevice d,File folder,Handler h,Listener l,int address,int bytes,int w,boolean p){
  device=d;dir=folder;main=h;listener=l;start=address;length=bytes;window=w;peer=p;
  if(length<=0||length%256!=0||window<1||window>8)throw new IllegalArgumentException("Invalid backup bounds");
  Race.flashRead(start,256);Race.flashRead(Math.addExact(start,length-256),256);
 }
 void start(){new Thread(()->run(),"bounded-earbud-backup").start();}
 void cancel(String reason){cancelReason=reason;cancelled=true;try{if(socket!=null)socket.close();}catch(IOException e){}}
 void log(String s){listener.log("BACKUP "+s);}
 byte[] nextFrame()throws Exception{
  while(received.isEmpty()){if(cancelled)throw new IOException(cancelReason);byte[] b=new byte[8192];int n=input.read(b);if(n<0)throw new EOFException("RACE stream closed");received.addAll(parser.feed(Arrays.copyOf(b,n)));}
  return received.removeFirst();
 }
 byte[] inner(byte[] f){return peer?Race.unwrap(f,destination):f;}
 byte[] exchange(int command,boolean toPeer)throws Exception{
  byte[] q=Race.query(command,0);if(toPeer)q=Race.peer(destination,q);deadline=SystemClock.elapsedRealtime()+8000;output.write(q);
  while(true){byte[] f=nextFrame();if(toPeer)f=Race.unwrap(f,destination);if(f!=null&&Race.reply(f,command)){deadline=0;return f;}}
 }
 JSONArray metadata(byte[] f)throws Exception{JSONArray a=new JSONArray();for(String s:new String(Arrays.copyOfRange(f,7,f.length),"US-ASCII").split("\u0000"))if(!s.isEmpty())a.put(s);return a;}
 void verifyBuild(JSONArray f)throws Exception{
  if(f.length()!=3||!f.getString(0).equals("ab157x_evk")||!f.getString(1).equals("IoT_SDK_for_BT_Audio_V3.10.0.c43sp_YFY_1")||!f.getString(2).equals("2026/10/12 15:44:12 GMT +08:00"))throw new IOException("Firmware differs from identified Pro 3 build");
 }
 String sha(File f)throws Exception{MessageDigest m=MessageDigest.getInstance("SHA-256");try(InputStream s=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=s.read(b))>0)m.update(b,0,n);}return Race.hex(m.digest()).replace(" ","");}
 void run(){
  long stamp=System.currentTimeMillis();String stem=String.format(Locale.ROOT,"backup-%s-%08x-%d-w%d-%d",peer?"peer":"local",start,length,window,stamp);
  File partial=new File(dir,stem+".partial"),complete=new File(dir,stem+".bin");boolean success=false;
  try{
   started=SystemClock.elapsedRealtime();report.put("started_at_ms",stamp);report.put("target",device.getAddress());report.put("role",peer?"discovered_peer":"connected_ear");report.put("physical_left_right_known",false);report.put("start",String.format(Locale.ROOT,"0x%08x",start));report.put("bytes_requested",length);report.put("window",window);report.put("page_size",256);report.put("hardware_writes",false);
   watchdog.scheduleAtFixedRate(()->{long now=SystemClock.elapsedRealtime();if((deadline>0&&now>deadline)||now-started>3600000)cancel("bounded read deadline expired");},250,250,TimeUnit.MILLISECONDS);
   socket=device.createRfcommSocketToServiceRecord(UUID.fromString("00000000-0000-0000-0099-aabbccddeeff"));deadline=SystemClock.elapsedRealtime()+12000;socket.connect();deadline=0;input=socket.getInputStream();output=socket.getOutputStream();
   JSONArray localBuild=metadata(exchange(0x1e08,false));verifyBuild(localBuild);report.put("connected_build",localBuild);
   if(peer){destination=Race.discoverPeer(exchange(0x0d00,false));report.put("peer_destination",Race.hex(destination));JSONArray peerBuild=metadata(exchange(0x1e08,true));verifyBuild(peerBuild);report.put("peer_build",peerBuild);}
   log("START role="+(peer?"peer":"local")+" bytes="+length+" window="+window+" file="+partial.getName());
   TreeMap<Integer,Long> pending=new TreeMap<>();TreeMap<Integer,byte[]> ready=new TreeMap<>();int nextSend=start,nextSave=start,end=start+length;
   try(FileOutputStream file=new FileOutputStream(partial)){
    while(nextSave<end){
     while(pending.size()<window&&nextSend<end){byte[] q=Race.flashRead(nextSend,256);if(peer)q=Race.peer(destination,q);pending.put(nextSend,SystemClock.elapsedRealtime());output.write(q);sent++;nextSend+=256;}
     if(pending.isEmpty())throw new IOException("Incomplete page sequence");deadline=Collections.min(pending.values())+8000;
     byte[] raw=nextFrame(),f=inner(raw);if(f==null||!Race.reply(f,0x0403))continue;
     if(f.length!=270)throw new IOException("Unexpected page response length");int address=(f[10]&255)|((f[11]&255)<<8)|((f[12]&255)<<16)|((f[13]&255)<<24);
     if(!pending.containsKey(address))throw new IOException("Duplicate or unrequested page response");byte[] data=Race.flashValue(f,address,256);pending.remove(address);ready.put(address,data);
     if(ready.size()>64)throw new IOException("Excessive out-of-order data");
     while(ready.containsKey(nextSave)){file.write(ready.remove(nextSave));nextSave+=256;completed++;if(completed*256%262144==0||nextSave==end)log("PROGRESS "+(completed*256)+"/"+length);}
    }
    if(!pending.isEmpty()||!ready.isEmpty()||sent!=length/256||completed!=length/256)throw new IOException("Page accounting mismatch");
    file.getFD().sync();
   }
   deadline=0;JSONArray finalBuild=metadata(exchange(0x1e08,peer));verifyBuild(finalBuild);report.put("build_after",finalBuild);
   if(peer){byte[] after=Race.discoverPeer(exchange(0x0d00,false));if(!Arrays.equals(destination,after))throw new IOException("Peer destination changed during backup");}
   if(partial.length()!=length||!partial.renameTo(complete))throw new IOException("Cannot finalize backup");
   report.put("file",complete.getName());report.put("sha256",sha(complete));success=true;log("COMPLETE "+complete.getName()+" sha256="+report.getString("sha256"));
  }catch(Exception e){log("STOP "+e);try{report.put("error",e.toString());report.put("partial_file",partial.getName());}catch(Exception ignored){}}
  finally{
   watchdog.shutdownNow();try{if(socket!=null)socket.close();}catch(IOException e){}
   try{report.put("complete",success);report.put("pages_sent",sent);report.put("pages_verified",completed);report.put("elapsed_ms",SystemClock.elapsedRealtime()-started);try(FileOutputStream f=new FileOutputStream(new File(dir,stem+".json"))){f.write(report.toString(2).getBytes("UTF-8"));}}catch(Exception e){log("MANIFEST_ERROR "+e);}
   main.post(()->listener.finished());
  }
 }
}
