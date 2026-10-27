package org.phoenix.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Bounded Java status handshake; validates a status JSON, not just an open TCP port. */
public final class MinecraftStatusProbe {
    private MinecraftStatusProbe() {}
    /**
     * The protocol number sent in the handshake: Minecraft 26.2's. Status replies don't depend on it, but relays that
     * inspect the handshake (Playit) reset connections announcing -1, the old "just pinging" convention.
     */
    public static final int STATUS_PROTOCOL=776;
    public static String query(String host,int port) throws IOException { return query(host,port,host,1200); }
    /**
     * Connects to [host]:[port] but names [handshakeHost] in the handshake, as a player's client does: a relay that
     * routes by hostname (Playit Free) sees exactly what it would see from that client. [timeoutMs] bounds connect and read.
     */
    public static String query(String host,int port,String handshakeHost,int timeoutMs) throws IOException {
        if(handshakeHost==null || handshakeHost.isEmpty() || handshakeHost.length()>255) throw new IOException("Invalid handshake host");
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(host,port),timeoutMs); socket.setSoTimeout(timeoutMs);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream handshake=new DataOutputStream(bytes);
            writeVarInt(handshake,0); writeVarInt(handshake,STATUS_PROTOCOL);
            byte[] address=handshakeHost.getBytes(StandardCharsets.UTF_8); writeVarInt(handshake,address.length); handshake.write(address); handshake.writeShort(port); writeVarInt(handshake,1);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream()); writeVarInt(out,bytes.size()); out.write(bytes.toByteArray()); out.write(new byte[]{1,0}); out.flush();
            DataInputStream in=new DataInputStream(socket.getInputStream()); int length=readVarInt(in);
            if(length<3 || length>131072) throw new IOException("Invalid status packet size");
            byte[] frame=new byte[length]; in.readFully(frame); DataInputStream payload=new DataInputStream(new ByteArrayInputStream(frame));
            if(readVarInt(payload)!=0) throw new IOException("Not a Minecraft status response");
            int size=readVarInt(payload); if(size<2 || size>payload.available()) throw new IOException("Invalid status JSON size");
            byte[] json=new byte[size]; payload.readFully(json); String result=new String(json,StandardCharsets.UTF_8);
            if(!result.startsWith("{") || !result.contains("\"version\"") || !result.contains("\"players\"")) throw new IOException("Invalid Minecraft status JSON");
            return result;
        }
    }
    public static boolean available(String host,int port) { try { query(host,port); return true; } catch(IOException e) { return false; } }
    static void writeVarInt(DataOutputStream out,int value) throws IOException { do { int bits=value & 127; value >>>=7; out.writeByte(bits | (value!=0 ? 128 : 0)); } while(value!=0); }
    static int readVarInt(DataInputStream in) throws IOException { int value=0; for(int i=0;i<5;i++) { int b=in.readUnsignedByte(); value|=(b & 127) << (7*i); if((b&128)==0)return value; } throw new IOException("Oversized VarInt"); }
}
