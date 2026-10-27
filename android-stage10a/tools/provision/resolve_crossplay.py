"""Explicit lock refresh for the official Geyser/Floodgate/Simple Voice Chat Fabric 26.2 profile."""
import hashlib, json, urllib.parse, urllib.request, zipfile, io
from pathlib import Path
root=Path(__file__).resolve().parents[2]
def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Phoenix/0.3 crossplay dependency audit'}),timeout=30).read()
build=json.loads(fetch('https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest'))
rows=[{'id':'geyser','version':build['version']+'-b'+str(build['build']),
       'url':f"https://download.geysermc.org/v2/projects/geyser/versions/{build['version']}/builds/{build['build']}/downloads/fabric",
       'fileName':'Geyser-Fabric.jar','expectedSha256':build['downloads']['fabric']['sha256']}]
for project in ('floodgate','fabric-api','simple-voice-chat'):
    q=urllib.parse.urlencode({'loaders':json.dumps(['fabric']),'game_versions':json.dumps(['26.2'])})
    version=json.loads(fetch(f'https://api.modrinth.com/v2/project/{project}/version?{q}'))[0]
    f=next(x for x in version['files'] if x['primary'])
    rows.append({'id':project,'version':version['version_number'],'url':f['url'],'fileName':f['filename'],'expectedSha512':f['hashes']['sha512']})
for row in rows:
    data=fetch(row['url'])
    if 'expectedSha256' in row: assert hashlib.sha256(data).hexdigest()==row['expectedSha256']
    if 'expectedSha512' in row: assert hashlib.sha512(data).hexdigest()==row['expectedSha512']
    row.update(sizeBytes=len(data),sha256=hashlib.sha256(data).hexdigest())
    with zipfile.ZipFile(io.BytesIO(data)) as jar: row['fabricMetadata']=json.loads(jar.read('fabric.mod.json'))
    cache=root/'.tools/crossplay-cache'; cache.mkdir(parents=True,exist_ok=True)
    (cache/row['fileName']).write_bytes(data)
manifest={'schemaVersion':1,'minecraftVersion':'26.2','javaTcpPort':25565,'bedrockUdpPort':19132,'artifacts':rows,
          'sourceDocs':['https://geysermc.org/wiki/geyser/setup/','https://geysermc.org/wiki/floodgate/setup/'],
          'notes':['Server-side crossplay and voice mods only; generic Minecraft runtime; no Phoenium or optimization mods.',
                   "Simple Voice Chat (All Rights Reserved) is downloaded from Modrinth's CDN at install time and never bundled in the APK."]}
text=json.dumps(manifest,indent=2,ensure_ascii=False)+'
'
(root/'CROSSPLAY_MANIFEST.json').write_text(text,encoding='utf8')
(root/'app/src/main/assets/CROSSPLAY_MANIFEST.json').write_text(text,encoding='utf8')
print(json.dumps([{k:r[k] for k in ('id','version','sizeBytes','sha256')}|{'depends':r['fabricMetadata'].get('depends')} for r in rows],indent=2))
