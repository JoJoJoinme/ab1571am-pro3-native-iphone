package org.earbud.repro.fota;
import android.app.*;import android.os.*;import android.content.*;import android.bluetooth.*;import android.view.*;import android.widget.*;
import org.json.*;import java.io.*;import java.util.*;import java.security.*;import java.util.concurrent.*;
import com.airoha.liblinker.*;import com.airoha.liblinker.model.*;import com.airoha.liblinker.host.*;
import com.airoha.libfota1568.fota.*;import com.airoha.libfota1568.fota.actionEnum.*;import com.airoha.libfota1568.fota.fotaInfo.*;
import com.airoha.libbase.constant.AgentPartnerEnum;
import com.airoha.liblogger.AirohaLogger;
import com.airoha.liblogger.LogInfo;import com.airoha.liblogger.printer.IPrinter;

public class MainActivity extends Activity {
 final Handler main=new Handler(Looper.getMainLooper());TextView ui;
 AirohaLinker linker;AirohaFotaMgr1568 mgr;SppLinkParam param;
 String phase="created",lastStage="",selectedHash="";boolean ready=false,failed=false,staged=false,committed=false;volatile boolean auditFailed=false;
 int batteryAgent=-1,batteryPeer=-1,stateAgent=-1,statePeer=-1;String versionAgent="",versionPeer="";int lastPercent=-1;boolean inspectionComplete=false;
 final BlockingQueue<byte[]> shaReplies=new LinkedBlockingQueue<>();volatile boolean stagedHashVerified=false,partitionsVerified=false;
 static final String ADDRESS="__TARGET_ADDRESS__";
 static final String TARGET_NAME="__TARGET_NAME__";
 volatile boolean activeHashesVerified=false;String[] activeMcuHashes={"",""};
 static final String ORIGINAL_MCU="4618fb38ff33a8453ced99e3d797b6ebd5f841039748116d7cc2bf6c7a474fb4";
 static final String PATCHED_MCU="4e472427dbb9c277a4ea030b2bf383a53e2c030ebd10f5d624e0dbab48c7b9e7";
 static final String FULL_CANDIDATE="5a9360a8abdda8231852985c5959d72b031e49bed0cdc84fa4b080e4b39796dc";
 static final String ORIGINAL_ROLLBACK="eb27b920ee9bd52a9b3fa9a3f8ac024e34d4e035d5228eb0d2f7e3020e23df46";
 static String hex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
 static long u32(byte[] b,int i){return (b[i]&255L)|((b[i+1]&255L)<<8)|((b[i+2]&255L)<<16)|((b[i+3]&255L)<<24);}
 void verifyPartitions(){phase="inspecting-partitions";new Thread(()->{try{
  shaReplies.clear();byte[] query=new com.airoha.libfota1568.RaceCommand.packet.fota.fotTws.RaceCmdFotaTwsQueryPartition(new byte[]{0}).getRaw((byte)0);if(!linker.send(ADDRESS,query))throw new IOException("Partition query not sent");long end=SystemClock.elapsedRealtime()+10000;
  while(SystemClock.elapsedRealtime()<end){byte[] r=shaReplies.poll(Math.max(1,end-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);if(r==null)break;event("PARTITION_REPLY",hex(r));if(r.length>=7&&r[1]==0x5b){if(r[6]!=0)throw new IOException("Partition query rejected");continue;}
   if(r.length!=26||r[1]!=0x5d||r[4]!=0x14||r[5]!=0x1c||r[6]!=0||r[7]!=0||r[8]!=0||r[17]!=0||u32(r,9)!=0x4b1000L||u32(r,18)!=0x4b1000L||u32(r,13)<2355200L+8192||u32(r,22)<2355200L+8192||u32(r,13)>3403776L||u32(r,22)>3403776L)throw new IOException("Partition scope does not match both backups");
   partitionsVerified=true;event("PARTITIONS_VERIFIED","both staging regions match backups");verifyActiveMcuHashes();return;
  }throw new IOException("Partition query timed out");
 }catch(Exception e){failed=true;phase="failed";event("PREFLIGHT_ERROR",e.toString());}},"partition-preflight").start();}
 void verifyStagedHashes(){
  phase="verifying-staged-hashes";stagedHashVerified=false;
  new Thread(()->{try{for(int role=0;role<2;role++){
   shaReplies.clear();byte[] q=StagedHashProof.query(role);
   if(!linker.send(ADDRESS,q))throw new IOException("SHA query transport unavailable");long end=SystemClock.elapsedRealtime()+30000;boolean matched=false;
   while(SystemClock.elapsedRealtime()<end){byte[] r=shaReplies.poll(Math.max(1,end-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);if(r==null)break;
    event("SHA_REPLY",hex(r));if(r.length>=7&&r[1]==0x5b){if(r[6]!=0)throw new IOException("SHA query rejected");continue;}
    String actual=StagedHashProof.validate(r,role,selectedHash);
    event("STAGED_HASH_MATCH",role+" "+actual);matched=true;break;
   }
   if(!matched)throw new IOException("No matching device-computed SHA for role "+role);
  }stagedHashVerified=true;phase="staged-independent-hashes-verified";event("VERIFICATION_COMPLETE",selectedHash);
  }catch(Exception e){failed=true;phase="verification-failed";event("VERIFICATION_ERROR",e.toString());}},"staged-hash-verifier").start();
 }
 synchronized void event(String kind,String value){
  try{JSONObject j=new JSONObject();j.put("at_ms",System.currentTimeMillis());j.put("event",kind);j.put("value",value);j.put("phase",phase);
   try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),"session.jsonl"),true)){f.write((j.toString()+"\n").getBytes("UTF-8"));}
  }catch(Exception e){android.util.Log.e("EarbudFota","Log failed",e);}
  android.util.Log.i("EarbudFota",kind+" "+value);
  main.post(()->{if(ui.length()>14000)ui.setText("完整验证记录保存在本机。\n");ui.append(kind+" "+value+"\n");});
 }
 String hash(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream s=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=s.read(b))>0)d.update(b,0,n);}StringBuilder x=new StringBuilder();for(byte v:d.digest())x.append(String.format(Locale.ROOT,"%02x",v&255));return x.toString();}
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);ui=new TextView(this);ui.setTextSize(16);ScrollView sc=new ScrollView(this);sc.addView(ui);setContentView(sc);if(checkSelfPermission("android.permission.BLUETOOTH_CONNECT")!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"},101);return;}try{
  String action=getIntent().getStringExtra("action");if(!"inspect".equals(action))throw new IllegalArgumentException("First launch must be inspection");
  BluetoothAdapter bt=((BluetoothManager)getSystemService(BLUETOOTH_SERVICE)).getAdapter();boolean match=false;
  for(BluetoothDevice d:bt.getBondedDevices())if(ADDRESS.equals(d.getAddress())&&TARGET_NAME.equals(d.getName()))match=true;
  if(!match)throw new IllegalStateException("Identified Pro 3 must remain paired");
  phase="connecting";event("ACTION","inspect only");AirohaLogger.getInstance().setLogLevel(AirohaLogger.LOG_LEVEL_D);
  AirohaLogger.getInstance().addPrinter(new IPrinter(){
   public boolean init(HashMap<String,String> c){return true;}public boolean init(String n,HashMap<String,String> c){return true;}public String getPrinterName(){return "local-upgrade-audit";}public void destroy(){}
   public synchronized void print(LogInfo info){String tag=info.getTag(),s=info.getText();if(s==null)return;if(!(tag.contains("SPP")||s.contains("cmd =")||s.contains("rsp =")||s.toLowerCase(Locale.ROOT).contains("commit")||s.toLowerCase(Locale.ROOT).contains("integrity")))return;try(FileOutputStream f=new FileOutputStream(new File(getFilesDir(),"packet-audit.log"),true)){f.write((info.getTimeStamp().getTime()+" "+tag+" "+s+"\n").getBytes("UTF-8"));}catch(Exception e){auditFailed=true;}}
  });
  linker=new AirohaLinker(this);param=new SppLinkParam(ADDRESS,UUID.fromString("00000000-0000-0000-0099-aabbccddeeff"));
  HashMap<String,HostStateListener> listeners=new HashMap<>();listeners.put("bounded-runner",new HostStateListener(){
   public void onHostConnected(){event("CONNECTED",ADDRESS);main.post(()->{if(!linker.init(ADDRESS)){failed=true;event("INIT_ERROR","transport initialization was not queued");}});}
   public void onHostDisconnected(){event("DISCONNECTED",phase);}
   public void onHostWaitingConnectable(){event("WAIT_CONNECT","");}
   public void onHostInitialized(){event("HOST_INITIALIZED","");main.post(()->connected());}
   public void onHostError(int error){failed=true;event("HOST_ERROR",String.valueOf(error));}
  });linker.connect(param,listeners);
 }catch(Exception e){failed=true;phase="failed";event("ERROR",e.toString());}}
 void connected(){if(mgr!=null)return;try{
  mgr=new AirohaFotaMgr1568(linker,param);mgr.setBackgroundModCmdCount(2);
  linker.getHost(ADDRESS).addHostDataListener("independent-sha-verifier",new HostDataListener(){public boolean onHostPacketReceived(byte[] p){if(p.length>=6&&(((p[4]&255)==0x31&&(p[5]&255)==4)||((p[4]&255)==0x14&&(p[5]&255)==0x1c)))shaReplies.offer(p.clone());return false;}public void onHostScheduleTimeout(TxScheduler.ITxScheduledData d){event("RAW_QUERY_TIMEOUT","");}});
  mgr.registerAirohaOtaListener(new AirohaFotaListener(){
   public void onProgressChanged(int p,AgentPartnerEnum role){if(p!=lastPercent){lastPercent=p;event("PROGRESS",role+" "+p);}}
   public void onTransferCompleted(){staged=true;phase="staged-awaiting-verification";event("TRANSFER_COMPLETE",selectedHash);}
   public void onCompleted(){event("SDK_COMPLETE",phase);}
   public void onFailed(AirohaFotaErrorEnum error){failed=true;phase="failed";event("SDK_ERROR",error.toString());}
   public void onRhoNotification(){event("ROLE_SWITCH","started");}
   public void onRhoCompleted(){event("ROLE_SWITCH","completed");}
   public void onTransferStartNotification(){event("TRANSFER_START","");}
   public void onDeviceRebooted(){phase="reboot-reported";event("REBOOT_REPORTED","requires independent readback");}
  });
  mgr.registerListener(new OnAirohaFotaStatusClientAppListener(){
   public void notifyError(String s){event("DETAIL_ERROR",s);}
   public void notifyStatus(String s){event("STATUS",s);}
   public void notifyCompleted(String s){if("inspecting".equals(phase)&&s.contains("FotaStage_00_TwsQueryState")){inspectionComplete=true;main.post(()->verifyPartitions());}event("DETAIL_COMPLETE",s);}
   public void notifyInterrupted(String s){event("INTERRUPTED",s);}
   public void notifyStateEnum(byte role,String s,int code){if(role==0)stateAgent=code;else if(role==1)statePeer=code;event("DEVICE_STATE",role+" "+s+" "+code);}
   public void notifyBatterLevelLow(){failed=true;event("LOW_BATTERY","");}
   public void notifyClientExistence(boolean b){event("PEER_EXISTS",String.valueOf(b));}
   public void onAvailableDualActionUpdated(DualActionEnum a){event("AVAILABLE_DUAL_ACTION",a.toString());}
   public void onAvailableSingleActionUpdated(SingleActionEnum a){event("AVAILABLE_SINGLE_ACTION",a.toString());}
   public void onDualFotaInfoUpdated(DualFotaInfo i){if(i!=null){versionAgent=i.agentVersion;versionPeer=i.clientVersion;event("DUAL_INFO",versionAgent+" | "+versionPeer);}}
   public void onSingleFotaInfoUpdated(SingleFotaInfo i){event("SINGLE_INFO","unexpected for this task");}
   public void onProgressUpdated(String s,int sc,int st,int pc,int pt){if(!s.equals(lastStage)){lastStage=s;event("STAGE",s+" "+sc+"/"+st+" "+pc+"/"+pt);}}
   public void onTransmitIntervalUpdated(byte role,short ms){event("INTERVAL",role+" "+ms);}
   public void onFotaComplete(){event("FOTA_CALLBACK_COMPLETE","");}
   public void onBatteryStatusReceived(byte role,int n){if(role==0)batteryAgent=n;else batteryPeer=n;event("BATTERY",role+" "+n);}
   public void onRHO(){event("RHO","");}
   public void onVersionReceived(byte role,String v){if(role==0)versionAgent=v;else versionPeer=v;event("VERSION",role+" "+v);}
   public void onAgentChannelReceived(boolean right){event("AGENT_CHANNEL",right?"right":"left");}
  });mgr.setFilePath(null);phase="inspecting";mgr.start(60,true,true,false);ready=true;
 }catch(Exception e){failed=true;phase="failed";event("ERROR",e.toString());}}
 @Override public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);String action=i.getStringExtra("action");try{
  if(!ready||mgr==null)throw new IllegalStateException("Inspection is not ready");
  if("inspect".equals(action)){if(staged||committed)throw new IllegalStateException("Do not restart inspection during update");mgr.setFilePath(null);phase="inspecting";failed=false;mgr.start(60,true,true,false);return;}
  if("stage".equals(action)||"stage-rollback".equals(action)){
   boolean rollback="stage-rollback".equals(action);String requiredMcu=rollback?PATCHED_MCU:ORIGINAL_MCU;if(!activeHashesVerified||!requiredMcu.equals(activeMcuHashes[0])||!requiredMcu.equals(activeMcuHashes[1]))throw new IllegalStateException("Both active MCU hashes must match the exact reviewed source for this direction");boolean statesOK=(stateAgent==65535&&statePeer==65535)||(rollback&&stateAgent==257&&statePeer==257);
   if(failed||!inspectionComplete||!partitionsVerified||staged||committed||batteryAgent<60||batteryPeer<60||!statesOK||!"V310.1.865.11".equals(versionAgent)||!versionAgent.equals(versionPeer))throw new IllegalStateException("Reviewed versions, known partitions/states, complete inspection and both batteries >=60 required");
   File f=new File(getFilesDir(),rollback?"rollback.fota.bin":"candidate.fota.bin");String h=hash(f);
   if(!(rollback?ORIGINAL_ROLLBACK:FULL_CANDIDATE).equals(h)||f.length()!=2355200)throw new IllegalArgumentException("Only reviewed package is accepted");
   selectedHash=h;phase="staging";event("ACTION","stage verified package, no commit");mgr.setFilePath(f.getAbsolutePath());mgr.start(60,true,true,false);return;
  }
  if("verify".equals(action)){if(failed||!staged||committed)throw new IllegalStateException("Completed staging required");verifyStagedHashes();return;}
  if("commit".equals(action)){
   if(failed||auditFailed||!staged||!stagedHashVerified||committed||stateAgent!=785||statePeer!=785)throw new IllegalStateException("Both independently verified hashes, ready states and packet audit required; commit is one-shot");
   if(!selectedHash.equals(i.getStringExtra("verified_hash")))throw new IllegalArgumentException("Staged hash confirmation mismatch");
   committed=true;phase="committing";event("ACTION","one-shot dual-ear commit");mgr.startCommitProcess(60);return;
  }
  if("cancel".equals(action)){if(committed)throw new IllegalStateException("Commit already dispatched; observe effect");phase="cancelling";mgr.cancel();event("ACTION","cancel staging");return;}
  throw new IllegalArgumentException("Unsupported action");
 }catch(Exception e){event("ACTION_REJECTED",e.toString());}}
 @Override public void onDestroy(){event("ACTIVITY_DESTROYED",phase);if(mgr!=null)mgr.destroy();if(linker!=null)linker.releaseAllResource();super.onDestroy();}
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==101&&grants.length>0&&grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)recreate();}

 void verifyActiveMcuHashes(){phase="checking-active-mcu";activeHashesVerified=false;
  new Thread(()->{try{for(int role=0;role<2;role++){
   shaReplies.clear();if(!linker.send(ADDRESS,StagedHashProof.activeQuery(role)))throw new IOException("Active hash query not sent");
   long end=SystemClock.elapsedRealtime()+30000;boolean matched=false;
   while(SystemClock.elapsedRealtime()<end){byte[] reply=shaReplies.poll(Math.max(1,end-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);if(reply==null)break;
    if(reply.length>=7&&reply[1]==0x5b){if(reply[6]!=0)throw new IOException("Active hash query rejected");continue;}
    activeMcuHashes[role]=StagedHashProof.activeValidate(reply,role);
    if(!ORIGINAL_MCU.equals(activeMcuHashes[role])&&!PATCHED_MCU.equals(activeMcuHashes[role]))throw new IOException("Unreviewed active MCU image");
    event("ACTIVE_MCU_HASH",role+" "+activeMcuHashes[role]);matched=true;break;
   }if(!matched)throw new IOException("Active hash query timed out");
  }if(!activeMcuHashes[0].equals(activeMcuHashes[1]))throw new IOException("Both ears must use the same reviewed MCU");activeHashesVerified=true;phase="inspected";event("ACTIVE_MCU_PREFLIGHT_OK","both exact hashes verified");
  }catch(Exception e){failed=true;phase="failed";event("PREFLIGHT_ERROR",e.toString());}},"source-mcu-check").start();
 }
}

