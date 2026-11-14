"""Download the six exact reference libraries; reject altered upstream files."""
from pathlib import Path
import hashlib,json,urllib.request,zipfile,io
ROOT=Path(__file__).resolve().parents[1]
items=json.loads((ROOT/'tools/sdk-sources.json').read_text())
dest=ROOT/'.vendor';dest.mkdir(exist_ok=True)
for item in items:
    path=dest/item['name']
    data=path.read_bytes() if path.exists() else urllib.request.urlopen(item['url'],timeout=30).read()
    if hashlib.sha256(data).hexdigest()!=item['sha256']:
        raise SystemExit('STOP: SDK hash mismatch: '+item['name'])
    if hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()!=item['git_blob_sha1']:
        raise SystemExit('STOP: SDK Git object mismatch')
    if not path.exists():path.write_bytes(data)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        (dest/(path.stem+'.jar')).write_bytes(z.read('classes.jar'))
    print('Verified '+item['name'])
