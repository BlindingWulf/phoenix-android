"""Verify bundle identity and that runtime account data is absent from the build artifact."""
import json,hashlib,zipfile,argparse
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('apk',nargs='?',default=str(root/'app/build/outputs/apk/debug/app-debug.apk'));p.add_argument('--netbird-aar',default=str(root/'app/libs/netbird-android-v0.80.0-debug.aar'));a=p.parse_args()
apk=Path(a.apk);issues=[];netbird_sha=None;packaged_abis=[]
with zipfile.ZipFile(apk) as z:
    names=z.namelist()
    for name in names:
        if any(part in name.split('/') for part in ('shared_prefs','no_backup','evidence','backups','servers')):issues.append('Runtime user-data directory bundled: '+name)
        if name.endswith(('.dat','.mca')):issues.append('World data bundled: '+name)
        if name.endswith(('eula.txt','keystore.properties','agent-key','.active')):issues.append('Private or accepted-user-state file bundled: '+name)
    manifest=json.loads(z.read('assets/DEPENDENCY_MANIFEST.json'))
    if manifest['minecraftVersion']!='26.2' or manifest['defaults']['eulaAccepted'] or manifest['defaults']['optimizationMods']:issues.append('Baseline defaults changed')
    host=json.loads(z.read('assets/runtime/host-manifest.json').decode('utf-8-sig'))
    # Only ABIs actually packaged are checked; arm64-v8a (the physical-device and NetBird ABI) is mandatory, and a
    # filtered ABI must not leave its JRE data asset behind.
    packaged_abis=sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.count('/')==2})
    if 'arm64-v8a' not in packaged_abis:issues.append('arm64-v8a native libraries missing')
    for abi in packaged_abis:
        if abi not in host['nativeHosts'] and f'lib/{abi}/libphoenixjvm.so' in names:issues.append('Native host without manifest entry: '+abi)
    for name in names:
        if name.startswith('assets/runtime/') and name.count('/')==3 and name.split('/')[2] not in packaged_abis:issues.append('Runtime asset for an unpackaged ABI: '+name)
    for abi,expected in host['nativeHosts'].items():
        if abi not in packaged_abis:continue
        actual=hashlib.sha256(z.read(f'lib/{abi}/libphoenixjvm.so')).hexdigest()
        if actual!=expected:issues.append('Host hash mismatch: '+abi)
        runtime=json.loads(z.read(f'assets/runtime/{abi}/runtime-manifest.json'))
        for native in runtime['native']:
            if hashlib.sha256(z.read(f"lib/{abi}/{native['installedName']}")).hexdigest()!=native['sha256']:issues.append('Native hash mismatch: '+native['installedName'])
    if hashlib.sha256(z.read('assets/runtime/phoenix-runtime.jar')).hexdigest()!=host['runtimeJarSha256']:issues.append('Runtime JAR hash mismatch')
    if 'lib/arm64-v8a/libgojni.so' not in names:issues.append('Embedded NetBird native client missing for the physical-device ABI')
    elif not Path(a.netbird_aar).is_file():issues.append('NetBird AAR build input unavailable for native identity verification')
    else:
        with zipfile.ZipFile(a.netbird_aar) as sdk:
            netbird_sha=hashlib.sha256(z.read('lib/arm64-v8a/libgojni.so')).hexdigest()
            if hashlib.sha256(sdk.read('jni/arm64-v8a/libgojni.so')).hexdigest()!=netbird_sha:issues.append('Embedded NetBird native library differs from its build input')
    if not any(name.endswith('.dex') and b'Lio/netbird/gomobile/android/Client;' in z.read(name) for name in names):issues.append('Embedded NetBird client bindings missing')
    # The official Playit agent (playitd) built from the pinned upstream source; tools/playit/BUILD_METADATA.txt names its hash.
    playit_expected=next((l.split(':',1)[1].strip().lower() for l in (root/'tools/playit/BUILD_METADATA.txt').read_text().splitlines() if l.startswith('Artifact SHA-256:')),None)
    playit_sha=None
    if 'lib/arm64-v8a/libplayitagent.so' not in names:issues.append('Playit agent missing for the physical-device ABI')
    else:
        playit_sha=hashlib.sha256(z.read('lib/arm64-v8a/libplayitagent.so')).hexdigest()
        if playit_sha!=playit_expected:issues.append('Playit agent differs from its recorded build')
    if 'assets/licenses/LICENSE-PLAYIT-AGENT.txt' not in names:issues.append('Playit agent BSD-2-Clause notice missing')
    for name in names:
        if name.endswith('.dex') and b'Ldev/phoenix/server/SecretStore;' in z.read(name):
            issues.append('Archived Playit key store included in executable DEX: '+name)
        if 'openport' in name.lower():issues.append('Archived OpenPort artifact bundled: '+name)
        if name.endswith('.dex') and b'OpenPort' in z.read(name):issues.append('OpenPort included in executable DEX: '+name)
        if name.endswith('/libphoenixjvm.so') and b'--openport' in z.read(name):issues.append('OpenPort launcher entry point included: '+name)
    if any(name.endswith(('server.jar','fabric-server-launch.jar')) for name in names):issues.append('Minecraft server redistribution unexpectedly bundled')
if issues:raise SystemExit('\n'.join(issues))
print(json.dumps({'result':'PASS','apk':str(apk),'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'abis':packaged_abis,'netbirdLibgojniSha256':netbird_sha,'playitAgentSha256':playit_sha,'checks':['host and native runtime SHA-256 (packaged ABIs: '+', '.join(packaged_abis)+')','NetBird libgojni.so SHA-256 matches its build-input AAR ('+Path(a.netbird_aar).name+')','NetBird gomobile client bindings present in DEX','no JRE data asset for an unpackaged ABI','Java 25 bootstrap SHA-256','Playit agent SHA-256 matches its recorded upstream build, BSD-2-Clause notice packaged','OpenPort classes and native artifacts excluded','no runtime user-data directories/worlds','no accepted EULA state','no server JARs bundled','no performance mods in defaults'],'limitations':['File/path exclusions are not a comprehensive secret-content audit','APK checks do not prove authenticated provisioning, DNS or game joins']},indent=2))
