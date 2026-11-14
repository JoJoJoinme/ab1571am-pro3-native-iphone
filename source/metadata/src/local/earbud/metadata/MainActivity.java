package org.earbud.repro.metadata;
import android.app.Activity;
import android.os.*;
import android.content.*;
import android.bluetooth.*;
import android.widget.TextView;
import android.util.Log;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

public class MainActivity extends Activity {
 static final String ADDRESS="__TARGET_ADDRESS__";
 static final String TARGET_NAME="__TARGET_NAME__";
 static final String HASH="4e472427dbb9c277a4ea030b2bf383a53e2c030ebd10f5d624e0dbab48c7b9e7";
 static final int CACHE=0x1423b29c;
 final Handler handler=new Handler(Looper.getMainLooper());
 volatile boolean stopped=false; BluetoothGatt gatt; BluetoothGattCharacteristic tx; TextView text;
 final BlockingQueue<byte[]> frames=new LinkedBlockingQueue<>();final Race.Parser parser=new Race.Parser();
 CountDownLatch ready=new CountDownLatch(1);volatile CountDownLatch ackLatch;volatile int transportStatus=-1;volatile String setupError;
 byte[] destination;int writes=0;
 synchronized void log(String value){Log.i("EarbudMetadata",value);runOnUiThread(()->text.append(value+"\n"));try(FileWriter w=new FileWriter(new File(getFilesDir(),"metadata.log"),true)){w.write(System.currentTimeMillis()+" "+value+"\n");}catch(Exception ignored){}}
 void require(boolean condition,String message)throws IOException{if(!condition)throw new IOException(message);}
 void save(String name,JSONObject object)throws Exception{
  try(FileOutputStream f=openFileOutput(name,MODE_PRIVATE)){f.write(object.toString(2).getBytes("UTF-8"));f.getFD().sync();}
 }
 JSONObject loadBaseline()throws Exception{try(InputStream f=openFileInput("metadata-baseline.json")){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[1024];int n;while((n=f.read(chunk))!=-1)b.write(chunk,0,n);return new JSONObject(b.toString("UTF-8"));}}
 String hex(byte[] b){return Race.hex(b).replace(" ","");}
 byte[] request(byte[] inner,boolean peer)throws Exception{
  require(!stopped,"Session stopped");int command=Race.command(inner);byte[] packet=peer?MetadataProtocol.relay(destination,inner):inner;
  frames.clear();ackLatch=new CountDownLatch(1);transportStatus=-1;
  log("TX role="+(peer?"peer":"local")+" command="+Integer.toHexString(command)+" bytes="+packet.length);
  int queued=gatt.writeCharacteristic(tx,packet,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
  require(queued==0,"Transport rejected request");
  if(command==0x0a01||command==0x1681){writes++;log("MUTATION_DISPATCHED role="+(peer?"peer":"local")+" command="+Integer.toHexString(command)+" attempt=1");}
  require(ackLatch.await(8,TimeUnit.SECONDS)&&transportStatus==0,"Transport acknowledgement missing; do not retry write");
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(command==0x0431?20:8);
  while(System.nanoTime()<deadline){
   byte[] raw=frames.poll(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);if(raw==null)break;
   byte[] f=peer?Race.unwrap(raw,destination):raw;if(f==null||!Race.reply(f,command))continue;
   if(command==0x0431&&f[1]==0x5b){require(f.length>=7&&f[6]==0,"Hash request rejected");continue;}
   return f;
  }
  throw new IOException("No matching reply; outcome unknown for any dispatched write; no automatic retry");
 }
 byte[] nv(int key,boolean peer)throws Exception{return Race.nv(request(Race.query(0x0a00,key),peer));}
 byte[] cache(boolean peer)throws Exception{return Race.infoCache(request(Race.query(0x1680,CACHE),peer));}
 String activeHash(int role)throws Exception{
  byte[] f=request(Race.activeHash(role),false);
  require(f.length==49&&f[1]==0x5d&&f[6]==0&&f[7]==0&&f[8]==(byte)role&&Arrays.equals(Arrays.copyOfRange(f,9,17),new byte[]{0,(byte)0xa0,0x1c,0,0,(byte)0xe0,0x23,0}),"Hash response scope mismatch");
  String sha=hex(Arrays.copyOfRange(f,17,49));require(HASH.equals(sha),"Unexpected active MCU; stop");return sha;
 }
 JSONObject snapshot()throws Exception{
  JSONObject s=new JSONObject();s.put("created_ms",System.currentTimeMillis());s.put("address",ADDRESS);
  s.put("mcu_0",activeHash(0));s.put("mcu_1",activeHash(1));
  destination=Race.discoverPeer(request(Race.query(0x0d00,0),false));s.put("destination",hex(destination));
  for(boolean peer:new boolean[]{false,true}){
   JSONObject ear=new JSONObject();String role=peer?"peer":"local";
   byte[] build=request(Race.query(0x1e08,0),peer);String info=new String(build,"US-ASCII");
   require(info.contains("2026/10/12")&&info.contains("ab157x_evk"),"Unexpected build identity");
   for(int key:new int[]{0xfc04,0xfb03,0xfb00}){byte[] value=nv(key,peer);require(value.length==1,"Invalid NV length");ear.put(Integer.toHexString(key),value[0]&255);}
   byte[] word=cache(peer);MetadataProtocol.enabledWord(word);ear.put("cache_word",hex(word));ear.put("loaded",word[0]&255);ear.put("runtime_fb00",word[1]&255);s.put(role,ear);
   log("SNAPSHOT "+role+" persistent_fb00="+ear.getInt("fb00")+" runtime_fb00="+(word[1]&255)+" loaded="+(word[0]&255));
  }
  return s;
 }
 void enableOne(boolean peer,JSONObject expected)throws Exception{
  String role=peer?"peer":"local";byte[] word=cache(peer);
  require(hex(word).equals(expected.getString("cache_word")),"Runtime cache changed since preflight");
  byte[] nv=nv(0xfb00,peer);require(nv.length==1&&(nv[0]&255)==expected.getInt("fb00"),"Persistent flag changed since preflight");
  require(Arrays.equals(word,cache(peer)),"Runtime cache not stable");
  if(nv[0]==0){MetadataProtocol.ack(request(MetadataProtocol.enableNv(),peer),0x0a01);require(Arrays.equals(nv(0xfb00,peer),new byte[]{1}),"Persistent readback mismatch");}
  else require(nv[0]==1,"Unexpected persistent flag");
  byte[] current=cache(peer);require(current[0]==word[0]&&current[2]==word[2]&&current[3]==word[3],"Adjacent bytes changed before synchronization");
  if(current[1]==0){require(Arrays.equals(current,cache(peer)),"Runtime cache changed before synchronization");MetadataProtocol.ack(request(MetadataProtocol.enableCache(current),peer),0x1681);}
  require(Arrays.equals(cache(peer),MetadataProtocol.enabledWord(word)),"Full cache word readback mismatch");
  for(int key:new int[]{0xfc04,0xfb03})require((nv(key,peer)[0]&255)==expected.getInt(Integer.toHexString(key)),"Other selected setting changed");
  log("VERIFIED "+role+" persistent_fb00=1 runtime_fb00=1 other_three_cache_bytes_preserved=true");
 }
 void work(String mode){
  try{
   require(ready.await(20,TimeUnit.SECONDS)&&setupError==null,"RACE channel not ready: "+setupError);
   JSONObject before=snapshot();
   if(mode.equals("inspect")){save("metadata-baseline.json",before);log("BASELINE_SAVED no_device_mutations=true");}
   else{
    JSONObject baseline=loadBaseline();
    for(String key:new String[]{"address","mcu_0","mcu_1","destination"})require(before.getString(key).equals(baseline.getString(key)),"Baseline identity mismatch");
    for(String role:new String[]{"local","peer"})for(String key:new String[]{"fc04","fb03","fb00","cache_word"})require(before.getJSONObject(role).get(key).equals(baseline.getJSONObject(role).get(key)),"Baseline state mismatch");
    save("metadata-before-apply.json",before);
    enableOne(false,before.getJSONObject("local"));enableOne(true,before.getJSONObject("peer"));
    JSONObject after=snapshot();
    for(String role:new String[]{"local","peer"})require(after.getJSONObject(role).getInt("fb00")==1&&after.getJSONObject(role).getInt("runtime_fb00")==1,"Final dual-ear state mismatch");
    after.put("writes_dispatched",writes);after.put("write_scope","FB00=1 and aligned cache word at 0x1423B29C preserving other three bytes");save("metadata-result.json",after);
    log("APPLY_COMPLETE both_ears_verified=true mcu_hashes_unchanged=true writes="+writes);
   }
   log("END completed");
  }catch(Exception e){log("ERROR "+e+" writes_dispatched="+writes);log("END stopped_without_retry");}
  finally{stopped=true;handler.post(()->{if(gatt!=null){gatt.disconnect();gatt.close();gatt=null;}});}
 }
 @Override public void onCreate(Bundle saved){super.onCreate(saved);text=new TextView(this);text.setTextSize(15);setContentView(text);
  if(checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"},101);return;}
  String mode=getIntent().getStringExtra("mode");if(mode==null)mode="inspect";
  if(!mode.equals("inspect")&&!mode.equals("apply")){log("Unsupported mode");return;}
  final String selected=mode;log("START mode="+selected);
  BluetoothAdapter adapter=((BluetoothManager)getSystemService(BLUETOOTH_SERVICE)).getAdapter();BluetoothDevice target=null;
  for(BluetoothDevice d:adapter.getBondedDevices())if(ADDRESS.equals(d.getAddress())&&TARGET_NAME.equals(d.getName()))target=d;
  if(target==null){log("Expected bonded device not found");return;}
  gatt=target.connectGatt(this,false,new BluetoothGattCallback(){
   @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){if(status==0&&state==BluetoothProfile.STATE_CONNECTED)g.discoverServices();else if(state==BluetoothProfile.STATE_DISCONNECTED&&!stopped){setupError="Disconnected";ready.countDown();}}
   @Override public void onServicesDiscovered(BluetoothGatt g,int status){try{
    if(status!=0)throw new IOException("Service discovery failed");BluetoothGattService s=g.getService(UUID.fromString("5052494d-2dab-0341-6972-6f6861424c45"));if(s==null)throw new IOException("RACE service missing");
    tx=s.getCharacteristic(UUID.fromString("43484152-2dab-3241-6972-6f6861424c45"));BluetoothGattCharacteristic rx=s.getCharacteristic(UUID.fromString("43484152-2dab-3141-6972-6f6861424c45"));
    if(tx==null||rx==null||(tx.getProperties()&8)==0||(rx.getProperties()&16)==0)throw new IOException("Invalid endpoints");
    BluetoothGattDescriptor d=rx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));if(d==null||!g.setCharacteristicNotification(rx,true)||g.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)!=0)throw new IOException("Notification setup failed");
   }catch(Exception e){setupError=e.toString();ready.countDown();}}
   @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){if(status!=0)setupError="Notification acknowledgement failed";ready.countDown();}
   @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){transportStatus=status;CountDownLatch latch=ackLatch;if(latch!=null)latch.countDown();}
   @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){try{synchronized(parser){for(byte[] f:parser.feed(value.clone()))frames.add(f);}}catch(Exception e){setupError=e.toString();}}
  },BluetoothDevice.TRANSPORT_BREDR);
  new Thread(()->work(selected),"earbud-metadata").start();
 }
 @Override public void onDestroy(){stopped=true;if(gatt!=null){gatt.disconnect();gatt.close();gatt=null;}super.onDestroy();}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==101&&grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)recreate();else log("Nearby-device permission not granted; stopped");}
 @Override public void onNewIntent(Intent i){super.onNewIntent(i);if(!stopped){log("BUSY: existing session still running");return;}setIntent(i);recreate();}
}
