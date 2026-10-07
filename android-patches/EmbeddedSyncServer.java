package com.ddms.ratio;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Minimalny serwer WebSocket działający bez dodatkowych bibliotek Androida. */
public final class EmbeddedSyncServer {
    private static final int PORT = 8787;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final Context context;
    private final Map<String, CopyOnWriteArrayList<Client>> rooms = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentHashMap<String, String>> records = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ServerSocket serverSocket;

    public EmbeddedSyncServer(Context context) { this.context = context.getApplicationContext(); }

    public synchronized String start() throws IOException {
        if (running) return getAddress();
        serverSocket = new ServerSocket(PORT, 20, InetAddress.getByName("0.0.0.0"));
        running = true;
        Thread acceptor = new Thread(() -> {
            while (running) {
                try {
                    final Socket socket = serverSocket.accept();
                    new Thread(() -> handle(socket), "ddms-sync-client").start();
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }, "ddms-sync-acceptor");
        acceptor.start();
        return getAddress();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        for (CopyOnWriteArrayList<Client> clients : rooms.values()) {
            for (Client client : clients) client.close();
        }
        rooms.clear();
    }

    public boolean isRunning() { return running; }

    public String getAddress() {
        return "ws://" + getLocalIp() + ":" + PORT;
    }

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { return "127.0.0.1"; }
        return "127.0.0.1";
    }

    private void handle(Socket socket) {
        Client client = null;
        try {
            socket.setKeepAlive(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String key = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(line.indexOf(':') + 1).trim();
            }
            if (key == null) { socket.close(); return; }
            String accept = Base64.encodeToString(sha1(key + WS_GUID), Base64.NO_WRAP);
            out.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            client = new Client(socket, in, out);
            while (running && client.readFrame()) { }
        } catch (Exception ignored) {
        } finally {
            if (client != null) remove(client);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1, current;
        while ((current = in.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] value = buffer.toByteArray();
                return new String(value, 0, Math.max(0, value.length - 1), StandardCharsets.US_ASCII);
            }
            buffer.write(current); previous = current;
        }
        return null;
    }

    private byte[] sha1(String value) throws Exception { return MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.US_ASCII)); }

    private void onMessage(Client client, String text) {
        try {
            JSONObject message = new JSONObject(text);
            if ("hello".equals(message.optString("type"))) {
                client.room = message.optString("room", "").trim();
                if (client.room.isEmpty()) return;
                rooms.computeIfAbsent(client.room, k -> new CopyOnWriteArrayList<>()).add(client);
                JSONArray state = new JSONArray();
                for (String value : records.computeIfAbsent(client.room, k -> new ConcurrentHashMap<>()).values()) state.put(new JSONObject(value));
                client.send(new JSONObject().put("type", "state").put("records", state).toString());
            } else if ("record".equals(message.optString("type")) && !client.room.isEmpty()) {
                JSONObject record = message.optJSONObject("record");
                if (record == null || record.optString("syncId", "").isEmpty()) return;
                String id = record.getString("syncId");
                records.computeIfAbsent(client.room, k -> new ConcurrentHashMap<>()).putIfAbsent(id, record.toString());
                for (Client peer : rooms.getOrDefault(client.room, new CopyOnWriteArrayList<>())) if (peer != client) peer.send(message.toString());
            }
        } catch (Exception ignored) { }
    }

    private void remove(Client client) {
        if (client.room != null) {
            CopyOnWriteArrayList<Client> clients = rooms.get(client.room);
            if (clients != null) clients.remove(client);
        }
    }

    private final class Client {
        final Socket socket; final InputStream in; final OutputStream out; String room = "";
        Client(Socket socket, InputStream in, OutputStream out) { this.socket = socket; this.in = in; this.out = out; }
        boolean readFrame() throws IOException {
            int first = in.read(), second = in.read();
            if (first < 0 || second < 0) return false;
            int opcode = first & 0x0f; boolean masked = (second & 0x80) != 0; long length = second & 0x7f;
            if (length == 126) length = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            else if (length == 127) { length = 0; for (int i = 0; i < 8; i++) length = (length << 8) | (in.read() & 0xff); }
            if (length > 8 * 1024 * 1024) return false;
            byte[] mask = masked ? readFully(4) : null, payload = readFully((int) length);
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            if (opcode == 8) return false;
            if (opcode == 1) onMessage(this, new String(payload, StandardCharsets.UTF_8));
            return true;
        }
        byte[] readFully(int length) throws IOException { byte[] b = new byte[length]; int offset = 0, n; while (offset < length && (n = in.read(b, offset, length - offset)) > 0) offset += n; if (offset != length) throw new IOException("eof"); return b; }
        synchronized void send(String text) { try { byte[] data = text.getBytes(StandardCharsets.UTF_8); if (data.length > 65535) return; out.write(0x81); if (data.length < 126) out.write(data.length); else { out.write(126); out.write((data.length >> 8) & 255); out.write(data.length & 255); } out.write(data); out.flush(); } catch (IOException ignored) { close(); } }
        void close() { try { socket.close(); } catch (IOException ignored) { } }
    }
}
