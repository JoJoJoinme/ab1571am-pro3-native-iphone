"""Binary-safe ADB file transfer for the three local reproduction helpers."""
from pathlib import Path
import argparse,hashlib,re,subprocess,sys

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--adb',default='adb');p.add_argument('--serial',required=True)
    p.add_argument('--app',choices=['readonly','metadata','fota'],required=True)
    sub=p.add_subparsers(dest='action',required=True)
    pull=sub.add_parser('pull');pull.add_argument('--name',required=True);pull.add_argument('--out',required=True)
    push=sub.add_parser('push');push.add_argument('--file',required=True);push.add_argument('--name',choices=['candidate.fota.bin','rollback.fota.bin'],required=True)
    a=p.parse_args();base=[a.adb,'-s',a.serial];package='org.earbud.repro.'+a.app
    if a.action=='pull':
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,160}',a.name):p.error('Use one file name from the helper files directory')
        out=Path(a.out);out.parent.mkdir(parents=True,exist_ok=True);temp=out.with_suffix(out.suffix+'.transfer-partial')
        if out.exists() or temp.exists():p.error('Output exists; choose a new output path')
        with temp.open('xb') as f:r=subprocess.run(base+['exec-out','run-as',package,'cat','files/'+a.name],stdout=f,stderr=subprocess.PIPE)
        if r.returncode:raise SystemExit('STOP: transfer failed; partial retained: '+r.stderr.decode(errors='replace'))
        temp.rename(out);print('Saved '+str(out)+' sha256='+hashlib.sha256(out.read_bytes()).hexdigest())
    else:
        if a.app!='fota':p.error('Package upload is restricted to the FOTA helper')
        source=Path(a.file);digest=hashlib.sha256(source.read_bytes()).hexdigest();remote='/data/local/tmp/earbud-repro-'+a.name
        subprocess.run(base+['push',str(source),remote],check=True)
        subprocess.run(base+['shell','run-as',package,'cp',remote,'files/'+a.name],check=True)
        r=subprocess.run(base+['exec-out','run-as',package,'sha256sum','files/'+a.name],capture_output=True,check=True)
        actual=r.stdout.decode().split()[0]
        if actual!=digest:raise SystemExit('STOP: phone file hash mismatch')
        print('Phone file verified sha256='+digest)

if __name__=='__main__':main()
