"""Read-only Minecraft Java status probe. A status response does not prove gameplay."""
import argparse,json,socket,struct,time
p=argparse.ArgumentParser(); p.add_argument('host'); p.add_argument('--port',type=int,default=25565); p.add_argument('--protocol',type=int,default=776); a=p.parse_args()
def varint(value):
    value&=0xffffffff; out=bytearray()
    while True:
        byte=value&127; value>>=7; out.append(byte|(128 if value else 0))
        if not value:return bytes(out)
def read_varint(stream):
    value=0
    for i in range(5):
        b=stream.read(1)
        if not b:raise EOFError()
        value|=(b[0]&127)<<(7*i)
        if b[0]<128:return value
    raise ValueError('Oversized varint')
host=a.host.encode(); packet=varint(0)+varint(a.protocol)+varint(len(host))+host+struct.pack('>H',a.port)+varint(1)
with socket.create_connection((a.host,a.port),3) as sock:
    sock.settimeout(3); sock.sendall(varint(len(packet))+packet+b'\x01\x00'); stream=sock.makefile('rb')
    size=read_varint(stream)
    if not 3<=size<=131072:raise ValueError('Oversized status frame')
    if read_varint(stream)!=0:raise ValueError('Wrong response packet')
    length=read_varint(stream)
    if length>size:raise ValueError('Wrong JSON length')
    data=json.loads(stream.read(length))
    print(json.dumps({'observedAtEpochMs':int(time.time()*1000),'target':f'{a.host}:{a.port}','status':data},indent=2))
