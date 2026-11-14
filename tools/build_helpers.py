"""Compile helpers bound to the reader's own paired device. Does not install or connect."""
from pathlib import Path
import argparse,json,os,re,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[1]

def command(args):subprocess.run([str(x) for x in args],check=True)
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--android-sdk',required=True);p.add_argument('--build-tools')
    p.add_argument('--address',required=True);p.add_argument('--name',required=True)
    p.add_argument('--app',choices=['all','readonly','metadata','fota'],default='all')
    a=p.parse_args();address=a.address.upper()
    if not re.fullmatch(r'(?:[0-9A-F]{2}:){5}[0-9A-F]{2}',address):p.error('Use your own earbud Bluetooth address AA:BB:CC:DD:EE:FF')
    if not a.name or any(ord(c)<32 for c in a.name):p.error('A nonempty device name without control characters is required')
    sdk=Path(a.android_sdk).resolve();android=sdk/'platforms/android-35/android.jar'
    if not android.is_file():android=sdk/'android-35/android.jar'
    if not android.is_file():raise SystemExit('STOP: install Android SDK Platform 35')
    if a.build_tools:bt=Path(a.build_tools).resolve()
    else:
        choices=list((sdk/'build-tools').glob('*')) if (sdk/'build-tools').exists() else []
        choices=[x for x in choices if x.is_dir() and re.fullmatch(r'\d+(?:\.\d+)+',x.name)]
        choices.sort(key=lambda x:tuple(map(int,x.name.split('.'))))
        bt=choices[-1] if choices else sdk/'android-15'
    exe='.exe' if os.name=='nt' else ''
    for path in [bt/('aapt2'+exe),bt/('zipalign'+exe),bt/'lib/d8.jar',bt/'lib/apksigner.jar',bt/'core-lambda-stubs.jar']:
        if not path.is_file():raise SystemExit('STOP: missing build tool '+str(path))
    for name in ['java','javac','keytool']:
        if not shutil.which(name):raise SystemExit('STOP: JDK tool missing: '+name)
    build_root=ROOT/'.build';build_root.mkdir(exist_ok=True);output=ROOT/'generated-apks';output.mkdir(exist_ok=True)
    key=build_root/'local-signing.p12'
    if not key.exists():command(['keytool','-genkeypair','-keystore',key,'-storepass','localdiagnostic','-keypass','localdiagnostic','-alias','diagnostic','-keyalg','RSA','-validity','365','-dname','CN=Local Earbud Reproduction','-noprompt'])
    apps=['readonly','metadata','fota'] if a.app=='all' else [a.app]
    names=['libBase-release','libLinker-release','libLogger-release','libUtils-release','libMgrCommon-release','libMgrFota1568-release']
    for app in apps:
        source=ROOT/'source'/app;build=build_root/app
        for n in ['src','classes','dex','generated']:(build/n).mkdir(parents=True,exist_ok=True)
        for file in (source/'src').rglob('*.java'):
            dest=build/'src'/file.relative_to(source/'src');dest.parent.mkdir(parents=True,exist_ok=True)
            text=file.read_text(encoding='utf-8').replace('"__TARGET_ADDRESS__"',json.dumps(address)).replace('"__TARGET_NAME__"',json.dumps(a.name))
            if '__TARGET_' in text:raise SystemExit('STOP: unbound source template')
            dest.write_text(text,encoding='utf-8')
        jars=[ROOT/'.vendor'/(name+'.jar') for name in names] if app=='fota' else []
        if not all(x.exists() for x in jars):raise SystemExit('STOP: run tools/fetch_sdk.py before building the FOTA helper')
        command([bt/('aapt2'+exe),'link','-I',android,'--manifest',source/'AndroidManifest.xml','--java',build/'generated','-o',build/'base.apk'])
        args=['javac','-encoding','UTF-8','-source','8','-target','8','-bootclasspath',str(bt/'core-lambda-stubs.jar')+os.pathsep+str(android),'-d',build/'classes']
        if jars:args+=['-classpath',os.pathsep.join(map(str,jars))]
        command(args+list((build/'src').rglob('*.java')))
        with zipfile.ZipFile(build/'classes.jar','w') as z:
            for f in (build/'classes').rglob('*.class'):z.write(f,f.relative_to(build/'classes').as_posix())
        command(['java','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','33','--lib',android,'--output',build/'dex',build/'classes.jar',*jars])
        with zipfile.ZipFile(build/'base.apk','a') as z:
            for f in (build/'dex').glob('*.dex'):z.write(f,f.name)
        command([bt/('zipalign'+exe),'-f','4',build/'base.apk',build/'aligned.apk'])
        apk=output/(app+'.apk')
        command(['java','-jar',bt/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:localdiagnostic','--out',apk,build/'aligned.apk'])
        command(['java','-jar',bt/'lib/apksigner.jar','verify',apk])
        print('Built '+str(apk))
    print('Target-bound APKs and signing material are private local output. Do not redistribute them as universal tools.')

if __name__=='__main__':main()
