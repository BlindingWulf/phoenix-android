"""Provision official Android SDK command-line tools into this project only."""
import hashlib, io, json, urllib.request, zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

root = Path(__file__).resolve().parents[1]
sdk = root / '.tools' / 'android-sdk'
sdk.mkdir(parents=True, exist_ok=True)
source = 'https://dl.google.com/android/repository/repository2-3.xml'
tree = ET.fromstring(urllib.request.urlopen(source, timeout=60).read())
def children(e, name):
    return [x for x in e.iter() if x.tag.split('}')[-1] == name]
packages = [e for e in children(tree, 'remotePackage') if e.attrib.get('path','').startswith('cmdline-tools;') and 'latest' not in e.attrib['path']]
packages.sort(key=lambda e: tuple(int(children(children(e,'revision')[0],n)[0].text or 0) if children(children(e,'revision')[0],n) else 0 for n in ('major','minor','micro')), reverse=True)
package = next(e for e in packages if not children(e,'channelRef') or children(e,'channelRef')[0].attrib.get('ref') == 'channel-0')
archive = next(e for e in children(package,'archive') if children(e,'host-os')[0].text == 'windows')
complete = children(archive,'complete')[0]
name = children(complete,'url')[0].text
checksum = children(complete,'checksum')[0]
url = 'https://dl.google.com/android/repository/' + name
print('Downloading', package.attrib['path'], url, flush=True)
data = urllib.request.urlopen(url, timeout=120).read()
actual = hashlib.new(checksum.attrib.get('type','sha1'), data).hexdigest()
if actual != checksum.text: raise RuntimeError('Official SDK archive checksum mismatch')
target = sdk / 'cmdline-tools' / 'latest'
with zipfile.ZipFile(io.BytesIO(data)) as z:
    for entry in z.infolist():
        parts = Path(entry.filename).parts
        relative = Path(*parts[1:])
        if '..' in relative.parts or relative.is_absolute(): raise RuntimeError('Invalid archive path')
        destination = target / relative
        if entry.is_dir(): destination.mkdir(parents=True, exist_ok=True)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(z.read(entry))
(sdk/'commandline-provenance.json').write_text(json.dumps({'metadata':source,'package':package.attrib['path'],'url':url,'sha256':hashlib.sha256(data).hexdigest()},indent=2))
print('SDK tools ready:', target, flush=True)
