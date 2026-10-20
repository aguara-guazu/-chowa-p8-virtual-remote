package local.chowa.remote;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ADB wire-protocol client for running shell commands on the local adbd.
 *
 * <p>Supports only unauthenticated transports ({@code ro.adb.secure=0}): an AUTH
 * request from adbd is reported as an {@link IOException}. Each call opens a new
 * TCP connection, performs CNXN, opens one {@code shell:} stream and collects
 * WRTE payloads until CLSE.
 */
final class AdbClient {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final int HEADER_SIZE = 24;
    private static final int LOCAL_ID = 1;
    private static final byte[] EMPTY = new byte[0];

    private final String host;
    private final int port;
    private final int timeoutMs;

    AdbClient(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    String shell(String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            send(out, A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
            Message msg = receive(in);
            if (msg.command == A_AUTH) {
                throw new IOException("adbd requires authentication (ro.adb.secure=1)");
            }
            if (msg.command != A_CNXN) {
                throw new IOException("unexpected adb handshake reply 0x" + Integer.toHexString(msg.command));
            }

            send(out, A_OPEN, LOCAL_ID, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (true) {
                msg = receive(in);
                if (msg.command == A_WRTE) {
                    result.write(msg.data);
                    send(out, A_OKAY, LOCAL_ID, msg.arg0, EMPTY);
                } else if (msg.command == A_CLSE) {
                    if (msg.arg0 != 0) {
                        send(out, A_CLSE, LOCAL_ID, msg.arg0, EMPTY);
                    }
                    return new String(result.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data)
            throws IOException {
        int checksum = 0;
        for (byte b : data) {
            checksum += b & 0xff;
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1)
                .putInt(data.length).putInt(checksum).putInt(~command);
        out.write(header.array());
        out.write(data);
        out.flush();
    }

    private static Message receive(DataInputStream in) throws IOException {
        byte[] raw = new byte[HEADER_SIZE];
        in.readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message msg = new Message();
        msg.command = header.getInt();
        msg.arg0 = header.getInt();
        msg.arg1 = header.getInt();
        int length = header.getInt();
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("invalid adb payload length " + length);
        }
        msg.data = new byte[length];
        in.readFully(msg.data);
        return msg;
    }

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }
}
