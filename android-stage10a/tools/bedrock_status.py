"""Read-only RakNet/Bedrock status probe; this does not perform player login."""
import argparse, json, socket, struct, time
from pathlib import Path
p=argparse.ArgumentParser(); p.add_argument('host'); p.add_argument('port',type=int); p.add_argument('--output'); a=p.parse_args()
magic=bytes.fromhex('00ffff00fefefefefdfdfdfd12345678'); stamp=time.time_ns()//1000000
ping=b'\x01'+struct.pack('>Q',stamp)+magic+struct.pack('>Q',0x50484f454e49584c)
start=time.perf_counter()
with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
    s.settimeout(10); s.connect((a.host,a.port)); s.send(ping); data=s.recv(2048)
assert len(data)>=35 and data[0]==0x1c and struct.unpack('>Q',data[1:9])[0]==stamp and data[17:33]==magic
length=struct.unpack('>H',data[33:35])[0]; assert len(data)>=35+length
motd=data[35:35+length].decode(); assert motd.startswith('MCPE;')
result={'host':a.host,'port':a.port,'protocol':'UDP RakNet unconnected ping','status':motd,'latencyMs':round((time.perf_counter()-start)*1000,2),'playerLoginTested':False}
text=json.dumps(result,indent=2)
if a.output:Path(a.output).write_text(text)
print(text)
