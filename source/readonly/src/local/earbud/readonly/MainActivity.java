package org.earbud.repro.readonly;
import android.app.Activity;
import android.os.*;
import android.content.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.widget.TextView;
import android.util.Log;
import java.util.*;
import java.io.*;
public class MainActivity extends Activity {
 final Handler h=new Handler(Looper.getMainLooper());
 TextView out; BluetoothAdapter adapter; BluetoothDevice target; BluetoothGatt gatt;
 BroadcastReceiver receiver; ScanCallback scanner; volatile boolean done=false;
 BluetoothGattCharacteristic tx; final Race.Parser parser=new Race.Parser(); byte[] peerDestination;
 FileOutputStream flashOut; File flashFile; int flashTotal=0,flashReceived=0;
 BluetoothSocket rfcomm;
 BackupEngine backup;
 void startRfcommFlash(){
  h.postDelayed(()->stop("RFCOMM session timeout"),Math.min(900000,15000+flashTotal/4));
  new Thread(()->{try{
   rfcomm=target.createRfcommSocketToServiceRecord(UUID.fromString("00000000-0000-0000-0099-aabbccddeeff"));
   Runnable connectTimeout=()->stop("RFCOMM connect timeout");h.postDelayed(connectTimeout,12000);rfcomm.connect();h.removeCallbacks(connectTimeout);if(done)return;
   log("RFCOMM_CONNECTED Airoha_APP");InputStream input=rfcomm.getInputStream();OutputStream output=rfcomm.getOutputStream();Race.Parser frames=new Race.Parser();byte[] buf=new byte[8192];
   while(!done&&!queries.isEmpty()){
    Query q=queries.poll();byte[] packet=q.cmd==0x0403?Race.flashRead(q.key,q.size):Race.query(q.cmd,q.key);
    Runnable timeout=()->stop("RFCOMM query timeout "+q.label());h.postDelayed(timeout,6000);log("TX "+q.label()+" bytes="+Race.hex(packet));output.write(packet);byte[] reply=null;
    while(!done&&reply==null){int n=input.read(buf);if(n<0)throw new IOException("RFCOMM stream closed");for(byte[] f:frames.feed(Arrays.copyOf(buf,n)))if(Race.reply(f,q.cmd)){reply=f;break;}}
    h.removeCallbacks(timeout);if(done)return;
    if(q.cmd==0x0403){byte[] page=Race.flashValue(reply,q.key,q.size);flashOut.write(page);flashReceived+=page.length;if(flashReceived%65536==0||flashReceived==flashTotal)log("FLASH_READ bytes="+flashReceived+"/"+flashTotal);}
    else{String text=new String(Arrays.copyOfRange(reply,7,reply.length),java.nio.charset.StandardCharsets.US_ASCII).replace('\0','|');log("METADATA "+q.label()+" text="+text);}
   }
   h.post(()->stop("read-only queries complete"));
  }catch(Exception e){log("RFCOMM_ERROR "+e);h.post(()->stop("RFCOMM read failed"));}},"earbud-read-only").start();
 }
 final ArrayDeque<Query> queries=new ArrayDeque<>(); Query awaiting; Runnable queryTimeout; boolean queryMode=false;
 boolean writeAcknowledged=false,replyReceived=false;
 void advanceWhenComplete(){if(done||awaiting==null||!writeAcknowledged||!replyReceived)return;h.removeCallbacks(queryTimeout);awaiting=null;h.post(()->sendNext());}
 static final class Query {final int cmd,key,size;final boolean peer;Query(int c,int k,boolean p){this(c,k,p,0);}Query(int c,int k,boolean p,int s){cmd=c;key=k;peer=p;size=s;}String label(){return (peer?"peer":"local")+String.format(Locale.ROOT," command=%04x key=%04x",cmd,key);}}
 void addReads(boolean peer){queries.add(new Query(0x0301,0,peer));queries.add(new Query(0x1e08,0,peer));for(int k:new int[]{0xfc04,0xfb03,0xfb00})queries.add(new Query(0x0a00,k,peer));}
 void sendNext(){
  if(done)return;awaiting=queries.poll();if(awaiting==null){stop("read-only queries complete");return;}writeAcknowledged=false;replyReceived=false;
  try{byte[] b=awaiting.cmd==0x0403?Race.flashRead(awaiting.key,awaiting.size):awaiting.cmd==0x0431?Race.activeHash(awaiting.key):Race.query(awaiting.cmd,awaiting.key);if(awaiting.peer)b=Race.peer(peerDestination,b);
   log("TX "+awaiting.label()+" bytes="+Race.hex(b));final Query q=awaiting;queryTimeout=()->stop("query timeout "+q.label());h.postDelayed(queryTimeout,q.cmd==0x0431?15000:6000);
   int s=gatt.writeCharacteristic(tx,b,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);if(s!=0)stop("write transport failed "+s);
  }catch(Exception e){log("ERROR "+e);stop("query failed");}
 }
 void received(byte[] bytes){
  if(done)return;try{for(byte[] raw:parser.feed(bytes)){
   if(awaiting==null||replyReceived)continue;
   if(awaiting.cmd!=0x0403&&awaiting.cmd!=0x1680)log("RX "+Race.hex(raw));
   byte[] f=awaiting.peer?Race.unwrap(raw,peerDestination):raw;if(f==null||!Race.reply(f,awaiting.cmd))continue;
   if(awaiting.cmd==0x0431&&f[1]==0x5b){if(f.length<7||f[6]!=0)throw new IOException("Active hash query rejected");continue;}
   if(awaiting.cmd==0x0403){byte[] page;try{page=Race.flashValue(f,awaiting.key,awaiting.size);}catch(Exception e){log("FLASH_REPLY_REJECT length="+f.length+" prefix="+Race.hex(Arrays.copyOf(f,Math.min(32,f.length))));throw e;}flashOut.write(page);flashReceived+=page.length;if(flashReceived%16384==0||flashReceived==flashTotal)log("FLASH_READ bytes="+flashReceived+"/"+flashTotal);}
   else if(awaiting.cmd==0x0a00)log("VALUE "+awaiting.label()+" value="+Race.hex(Race.nv(f)));
   else if(awaiting.cmd==0x1680){byte[] word=Race.infoCache(f);log("INFO_CACHE "+awaiting.label()+" loaded="+(word[0]&255)+" fb00="+(word[1]&255)+" other_bytes=not_logged");}
   else if(awaiting.cmd==0x0d00){peerDestination=Race.discoverPeer(f);log("PEER "+Race.hex(peerDestination));}
   else if(awaiting.cmd==0x1c00){if(f.length!=17||f[6]!=0)throw new IOException("FOTA partition query rejected: "+Race.hex(f));long address=0,size=0;for(int i=0;i<4;i++){address|=(long)(f[9+i]&255)<<(i*8);size|=(long)(f[13+i]&255)<<(i*8);}log("FOTA_INFO "+awaiting.label()+" start="+Long.toHexString(address)+" bytes="+size+" raw="+Race.hex(f));}
   else if(awaiting.cmd==0x0431){if(f.length!=49||f[1]!=0x5d||f[6]!=0||f[7]!=0||f[8]!=(byte)awaiting.key||!Arrays.equals(Arrays.copyOfRange(f,9,17),new byte[]{0,(byte)0xa0,0x1c,0,0,(byte)0xe0,0x23,0}))throw new IOException("Active hash scope mismatch");String sha=Race.hex(Arrays.copyOfRange(f,17,49)).replace(" ","");log("ACTIVE_MCU_SHA role="+awaiting.key+" sha256="+sha+" matchesCandidate="+sha.equals("4e472427dbb9c277a4ea030b2bf383a53e2c030ebd10f5d624e0dbab48c7b9e7"));}
   else{String text=new String(Arrays.copyOfRange(f,7,f.length),java.nio.charset.StandardCharsets.US_ASCII).replace('\0','|');log("METADATA "+awaiting.label()+" text="+text);}
   replyReceived=true;advanceWhenComplete();
  }}catch(Exception e){log("ERROR "+e);stop("response rejected");}
 }
 synchronized void log(String s){Log.i("EarbudReadOnly",s);if(!(flashTotal>0&&(s.startsWith("TX local command=0403")||s.startsWith("WRITE_ACK"))))runOnUiThread(()->{if(out.length()>12000)out.setText("完整记录保存在本机。\n");out.append(s+"\n");});try(FileWriter f=new FileWriter(new File(getFilesDir(),"probe.log"),true)){f.write(System.currentTimeMillis()+" "+s+"\n");}catch(Exception e){}}
 synchronized void stop(String reason){if(done)return;done=true;log("END "+reason);h.removeCallbacksAndMessages(null);if(rfcomm!=null){try{rfcomm.close();}catch(IOException e){log("CLOSE_ERROR "+e);}rfcomm=null;}if(flashOut!=null){try{flashOut.close();if(reason.equals("read-only queries complete")&&flashReceived==flashTotal){File complete=new File(getFilesDir(),flashFile.getName().replace(".partial",".bin"));if(!flashFile.renameTo(complete))throw new IOException("Cannot finalize backup");log("FLASH_FILE "+complete.getName()+" bytes="+flashReceived);}else log("FLASH_PARTIAL bytes="+flashReceived);}catch(Exception e){log("FILE_ERROR "+e);}flashOut=null;}if(gatt!=null){gatt.disconnect();gatt.close();gatt=null;}if(scanner!=null)adapter.getBluetoothLeScanner().stopScan(scanner);if(receiver!=null){unregisterReceiver(receiver);receiver=null;}}
 @Override public void onCreate(Bundle b){super.onCreate(b);out=new TextView(this);out.setTextSize(15);setContentView(out);if(checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"},101);return;}try{
 String mode=getIntent().getStringExtra("mode");if(mode==null)mode="inventory";
 log("START mode="+mode);adapter=((BluetoothManager)getSystemService(BLUETOOTH_SERVICE)).getAdapter();
 String addr=getIntent().getStringExtra("address");String expectedName=getIntent().getStringExtra("name");if(expectedName==null)expectedName="__TARGET_NAME__";int matches=0;
 for(BluetoothDevice d:adapter.getBondedDevices())if(d.getAddress().equals(addr)&&expectedName.equals(d.getName())){target=d;matches++;}
 if(matches!=1)throw new IllegalStateException("Exactly one matching bonded earbud required");
 log("TARGET name="+target.getName()+" type="+target.getType()+" bond="+target.getBondState()+" UUIDs="+Arrays.toString(target.getUuids()));
 if(mode.equals("backup")){
  backup=new BackupEngine(target,getFilesDir(),h,new BackupEngine.Listener(){public void log(String s){MainActivity.this.log(s);}public void finished(){stop("backup session finished");}},Integer.decode(getIntent().getStringExtra("start")),getIntent().getIntExtra("size",0),getIntent().getIntExtra("window",1),getIntent().getBooleanExtra("peer",false));backup.start();return;
 }
 if(mode.equals("inventory")){stop("inventory complete");return;}
 if(mode.equals("sdp")){
 receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(d!=null&&d.equals(target)){log("SDP UUIDs="+Arrays.toString(i.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)));stop("sdp complete");}}};
 registerReceiver(receiver,new IntentFilter(BluetoothDevice.ACTION_UUID));log("SDP started="+target.fetchUuidsWithSdp());h.postDelayed(()->stop("sdp timeout"),15000);return;}
 if(mode.equals("scan")){
 scanner=new ScanCallback(){public void onScanResult(int type,ScanResult r){log("SCAN name="+r.getDevice().getName()+" address="+r.getDevice().getAddress()+" connectable="+r.isConnectable()+" rssi="+r.getRssi()+" record="+r.getScanRecord());}public void onScanFailed(int code){stop("scan failed "+code);}};
 List<ScanFilter> filters=Arrays.asList(new ScanFilter.Builder().setDeviceName("cc ultimate").build(),new ScanFilter.Builder().setDeviceAddress(addr).build(),new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("5052494d-2dab-0341-6972-6f6861424c45")).build());
 adapter.getBluetoothLeScanner().startScan(filters,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanner);h.postDelayed(()->stop("scan window complete"),12000);return;}
 queryMode=mode.equals("read-info")||mode.equals("read-config")||mode.equals("read-info-cache")||mode.equals("read-flash")||mode.equals("rfcomm-flash")||mode.equals("read-fota-info")||mode.equals("read-active-hashes");
 if(queryMode){if(mode.equals("read-config")||mode.equals("read-info-cache")){addReads(false);if(mode.equals("read-info-cache"))queries.add(new Query(0x1680,0x1423b29c,false));queries.add(new Query(0x0d00,0,false));addReads(true);if(mode.equals("read-info-cache"))queries.add(new Query(0x1680,0x1423b29c,true));}else{queries.add(new Query(0x0301,0,false));queries.add(new Query(0x1e08,0,false));}}
 if(mode.equals("read-fota-info")){queries.add(new Query(0x1c00,0,false));queries.add(new Query(0x0d00,0,false));queries.add(new Query(0x0301,0,true));queries.add(new Query(0x1e08,0,true));queries.add(new Query(0x1c00,0,true));}
 if(mode.equals("read-active-hashes")){queries.add(new Query(0x0431,0,false));queries.add(new Query(0x0431,1,false));}
 if(mode.equals("read-flash")||mode.equals("rfcomm-flash")){
  int start=Integer.decode(getIntent().getStringExtra("start"));flashTotal=getIntent().getIntExtra("size",0);
  int page=getIntent().getIntExtra("page",256);Race.flashRead(start,page);
  if(flashTotal<=0||flashTotal>0x800000||flashTotal%page!=0||(long)start+flashTotal>0x08800000L)throw new IllegalArgumentException("Invalid bounded flash read");
  flashFile=new File(getFilesDir(),String.format(Locale.ROOT,"flash-%08x-%d-%d.partial",start,flashTotal,System.currentTimeMillis()));flashOut=new FileOutputStream(flashFile);
  for(int offset=0;offset<flashTotal;offset+=page)queries.add(new Query(0x0403,start+offset,false,page));
 }
 if(mode.equals("rfcomm-flash")){startRfcommFlash();return;}
 if(!mode.equals("le")&&!mode.equals("bredr")&&!queryMode)throw new IllegalArgumentException("Unsupported mode");
 final int transport=mode.equals("le")?BluetoothDevice.TRANSPORT_LE:BluetoothDevice.TRANSPORT_BREDR;
 log("CONNECT transport="+transport);
 gatt=target.connectGatt(this,false,new BluetoothGattCallback(){
 @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){log("GATT status="+status+" state="+state);if(status==0&&state==BluetoothProfile.STATE_CONNECTED){log("DISCOVER started="+g.discoverServices());}else if(state==BluetoothProfile.STATE_DISCONNECTED)h.post(()->stop("disconnected "+status));}
 @Override public void onServicesDiscovered(BluetoothGatt g,int status){log("SERVICES status="+status);for(BluetoothGattService s:g.getServices()){log("SERVICE "+s.getUuid());for(BluetoothGattCharacteristic c:s.getCharacteristics())log("CHAR "+c.getUuid()+" properties="+c.getProperties());}if(!queryMode){h.post(()->stop("service discovery complete"));return;}h.post(()->{try{if(status!=0)throw new IllegalStateException("Service discovery failed");BluetoothGattService s=g.getService(UUID.fromString("5052494d-2dab-0341-6972-6f6861424c45"));if(s==null)throw new IllegalStateException("RACE service absent");tx=s.getCharacteristic(UUID.fromString("43484152-2dab-3241-6972-6f6861424c45"));BluetoothGattCharacteristic rx=s.getCharacteristic(UUID.fromString("43484152-2dab-3141-6972-6f6861424c45"));if(tx==null||rx==null||(tx.getProperties()&8)==0||(rx.getProperties()&16)==0)throw new IllegalStateException("RACE endpoints invalid");BluetoothGattDescriptor d=rx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));if(d==null||!g.setCharacteristicNotification(rx,true))throw new IllegalStateException("Notify unavailable");int v=g.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);log("SUBSCRIBE queued="+v);if(v!=0)stop("Subscribe failed");}catch(Exception e){log("ERROR "+e);stop("service validation failed");}});}
 @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){log("SUBSCRIBE status="+status);h.post(()->{if(status==0)sendNext();else stop("Subscribe rejected");});}
 @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){log("WRITE_ACK status="+status);h.post(()->{if(done||g!=gatt)return;if(status!=0){stop("query transport rejected");return;}writeAcknowledged=true;advanceWhenComplete();});}
 @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){byte[] copy=value.clone();h.post(()->received(copy));}
 },transport);h.postDelayed(()->stop("session timeout"),flashTotal>0?Math.min(900000,10000+flashTotal/4):queryMode?90000:25000);
 }catch(Exception e){log("ERROR "+e);stop("error");}}
 @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);if(!done){log("BUSY: another read-only session is still active");return;}setIntent(intent);recreate();}
 @Override public void onDestroy(){if(backup!=null)backup.cancel("activity destroyed");stop("activity destroyed");super.onDestroy();}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==101&&grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)recreate();}
}

