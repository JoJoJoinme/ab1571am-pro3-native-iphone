"""Offline, exact-image-locked patching and packaging. No Bluetooth or device writes."""
import argparse, hashlib, json, struct, sys
from pathlib import Path
from nvdm_reader import parse as parse_nvdm

ORIGINAL = '4618fb38ff33a8453ced99e3d797b6ebd5f841039748116d7cc2bf6c7a474fb4'
PATCHED = '4e472427dbb9c277a4ea030b2bf383a53e2c030ebd10f5d624e0dbab48c7b9e7'
OTA_PATCHED = '5a9360a8abdda8231852985c5959d72b031e49bed0cdc84fa4b080e4b39796dc'
OTA_ORIGINAL = 'eb27b920ee9bd52a9b3fa9a3f8ac024e34d4e035d5228eb0d2f7e3020e23df46'
BOOT = 'd317425d5cd7a0525fd80c719fb3caef9474b8bd50d93c19794cabba7275de28'
DSP = '5f4f3e3da51cfd62163b7f1234fccc8123a3b89bd520ed7cf254ba3df022e469'
SIZE, OFFSET = 2351104, 0x99B1A

def sha(data): return hashlib.sha256(data).hexdigest()
def require(ok, message):
    if not ok: raise ValueError(message)

def mcu_from(data):
    if len(data) == 8388608: return data[0x1CA000:0x408000]
    require(len(data) == SIZE, 'Expected an exact 8 MiB backup or reviewed MCU partition')
    return data

def check_original(data):
    mcu = mcu_from(data)
    require(sha(mcu) == ORIGINAL, 'Unknown or already modified MCU: stop; same chipset/build text is not sufficient')
    require(mcu[OFFSET:OFFSET+2] == b'\x00\x20', 'Instruction mismatch')
    return mcu

def check_backup(path, manifest_path):
    data = Path(path).read_bytes()
    require(len(data) == 8388608, 'A complete 8 MiB backup from each ear is required')
    manifest = json.loads(Path(manifest_path).read_text(encoding='utf-8-sig'))
    require(manifest.get('complete') is True and manifest.get('sha256') == sha(data), 'Phone backup manifest/hash mismatch')
    require(manifest.get('pages_verified') == 32768 and manifest.get('hardware_writes') is False, 'Backup accounting is incomplete')
    check_original(data)
    require(sha(data[0x3000:0x13000]) == BOOT, 'Bootloader differs from tested profile')
    require(sha(data[0x14000:0x1CA000]) == DSP, 'DSP image differs from tested profile')
    partitions = []
    for pos in range(0, 0x210, 0x30):
        address, length, kind = struct.unpack_from('<I', data, pos+12)[0], struct.unpack_from('<I', data, pos+20)[0], struct.unpack_from('<I', data, pos+48)[0]
        partitions.append((address, length, kind))
    require((0x081CA000, SIZE, 4) in partitions and (0x084B1000, 3403776, 7) in partitions and (0x087F0000, 65536, 15) in partitions, 'Partition map mismatch')
    records = parse_nvdm(data[0x7F0000:])
    require(len(records) > 0, 'No validated device-specific settings')
    flags = {}
    for key in ('FC04','FB03','FB00'):
        matches = [x for x in records if x['key'].upper() == key]
        require(len(matches) == 1 and matches[0]['length'] == 1, 'Missing/ambiguous config key '+key)
        flags[key] = matches[0]['value'][0]
    require(flags['FC04'] == 0 and flags['FB03'] == 1 and flags['FB00'] in (0,1), 'Configuration differs from the tested recipe; review separately')
    return {'file':str(path), 'sha256':sha(data), 'role':manifest.get('role'), 'target':manifest.get('target'), 'mcu_sha256':ORIGINAL, 'valid_nvdm_records':len(records), 'flags':flags}

def patch(data):
    mcu = check_original(data); result = bytearray(mcu); result[OFFSET] = 1
    require(sha(result) == PATCHED, 'Patched image hash mismatch')
    require([i for i,(a,b) in enumerate(zip(mcu,result)) if a!=b] == [OFFSET], 'Unexpected byte changes')
    return bytes(result)

def package(mcu):
    require(len(mcu)==SIZE and sha(mcu) in (ORIGINAL,PATCHED), 'Unreviewed payload')
    def tlv(t,v): return struct.pack('<HH',t,len(v))+v
    body = tlv(0x11,struct.pack('<BBII',0,1,4096,SIZE))
    body += tlv(0x12,struct.pack('<IIII',1,4096,SIZE,0x1CA000))
    body += tlv(0x13,b'V310.1.865.11')
    body += tlv(0x14,struct.pack('<I',1)+hashlib.sha256(mcu).digest())
    body += tlv(0xF0,b'\0')
    body += b'\xff'*(4096-256-len(body))+mcu
    out = hashlib.sha256(body).digest()+b'\xff'*224+body
    require(sha(out)==(OTA_PATCHED if sha(mcu)==PATCHED else OTA_ORIGINAL), 'Package hash mismatch')
    return out

def exclusive_write(path,data):
    path=Path(path)
    if path.exists():
        require(path.read_bytes()==data, 'Refusing to overwrite different existing file: '+str(path))
        return
    with path.open('xb') as f:f.write(data)

def generate(left, right, lm, rm, out):
    reports=[check_backup(left,lm),check_backup(right,rm)]
    require({x['role'] for x in reports}=={'connected_ear','discovered_peer'}, 'Need connected-ear and discovered-peer backups')
    require(reports[0]['target']==reports[1]['target'], 'Backups do not belong to the same captured pair')
    original=check_original(Path(left).read_bytes());modified=patch(original)
    out=Path(out);out.mkdir(parents=True,exist_ok=True)
    files={'pro3-apple-native.fota.bin':package(modified),'pro3-original-rollback.fota.bin':package(original)}
    for name,data in files.items():exclusive_write(out/name,data)
    record={'profile':'AB157x 2026-10-12 V310.1.865.11 exact-image experiment','private_local_record':True,'backups':reports,'artifacts':[{'file':name,'bytes':len(data),'sha256':sha(data)} for name,data in files.items()], 'note':'Package excludes NVDM and DSP updates, but MCU image may itself contain factory/device identifiers; keep generated binaries private.'}
    exclusive_write(out/'build-result.json',(json.dumps(record,ensure_ascii=False,indent=2)+'\n').encode())
    return {'generated':record['artifacts'],'device_writes':0}

def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    c=sub.add_parser('check');c.add_argument('image')
    g=sub.add_parser('generate');g.add_argument('--connected',required=True);g.add_argument('--connected-manifest',required=True);g.add_argument('--peer',required=True);g.add_argument('--peer-manifest',required=True);g.add_argument('--out',required=True)
    v=sub.add_parser('verify-package');v.add_argument('package')
    a=p.parse_args()
    if a.command=='check':m=check_original(Path(a.image).read_bytes());result={'compatible_mcu':True,'sha256':sha(m),'bytes':len(m)}
    elif a.command=='generate':result=generate(a.connected,a.peer,a.connected_manifest,a.peer_manifest,a.out)
    else:
        data=Path(a.package).read_bytes();require(len(data)==2355200 and sha(data) in (OTA_PATCHED,OTA_ORIGINAL),'Unknown package');require(data==package(data[4096:]),'Invalid package structure');result={'valid_package':True,'sha256':sha(data),'payload_sha256':sha(data[4096:])}
    print(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,KeyError,json.JSONDecodeError) as e:print('STOP: '+str(e),file=sys.stderr);sys.exit(2)
