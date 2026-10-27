package org.phoenix.core.network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Real RakNet unconnected ping, distinct from UDP socket/process liveness. */
public final class BedrockStatusProbe {
    private static final byte[] MAGIC = {0, (byte)255, (byte)255, 0, (byte)254, (byte)254, (byte)254, (byte)254, (byte)253, (byte)253, (byte)253, (byte)253, 0x12, 0x34, 0x56, 0x78};
    private BedrockStatusProbe() {}
    public static String query(String host, int port, int timeoutMillis) throws IOException {
        long timestamp = System.nanoTime();
        byte[] ping = ByteBuffer.allocate(33).put((byte)1).putLong(timestamp).put(MAGIC).putLong(0x50484f454e49584cL).array();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(host), port);
            socket.setSoTimeout(timeoutMillis);
            socket.send(new DatagramPacket(ping, ping.length));
            byte[] data = new byte[2048];
            DatagramPacket pong = new DatagramPacket(data, data.length);
            socket.receive(pong);
            if (pong.getLength() < 35 || data[0] != (byte)0x1c || ByteBuffer.wrap(data,1,8).getLong()!=timestamp || !Arrays.equals(MAGIC,Arrays.copyOfRange(data,17,33))) throw new IOException("Invalid Bedrock status response");
            int length = Short.toUnsignedInt(ByteBuffer.wrap(data,33,2).getShort());
            if (35+length > pong.getLength()) throw new IOException("Truncated Bedrock status response");
            String status = new String(data,35,length,StandardCharsets.UTF_8);
            if (!status.startsWith("MCPE;")) throw new IOException("Not a Bedrock status response");
            return status;
        }
    }
}
