package com.yinrg.dts;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DtsClient {
    public interface Listener {
        default void onConnected() {
        }

        default void onDisconnected(String reason) {
        }

        default void onError(String message) {
        }

        default void onScreenSize(int width, int height) {
        }

        default void onTileSize(int width, int height) {
        }

        default void onFullImage(byte[] jpeg) {
        }

        default void onTile(int x, int y, byte[] jpeg) {
        }

        default void onTilesSame(int[] xs, int[] ys, byte[] jpeg) {
        }

        default void onTilesBatch(Tile[] tiles) {
        }

        default void onDelay(double seconds) {
        }

        default void onPasswordRequired() {
        }

        default void onBadPassword() {
        }

        default void onControlState(boolean allowed) {
        }

        default void onWebPath(String path) {
        }

        default void onServerCommand(String command) {
        }
    }

    public static final class Tile {
        public final int x;
        public final int y;
        public final byte[] jpeg;

        public Tile(int x, int y, byte[] jpeg) {
            this.x = x;
            this.y = y;
            this.jpeg = jpeg;
        }
    }

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final Listener listener;
    private final Object sendLock = new Object();
    private final LinkedBlockingQueue<byte[]> outbound = new LinkedBlockingQueue<>(2048);
    private final AtomicBoolean disconnectNotified = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile boolean writerRunning;
    private volatile Socket socket;
    private Thread thread;
    private Thread writerThread;
    private boolean requestedFull;

    public DtsClient(String host, int port, String user, String password, Listener listener) {
        this.host = host;
        this.port = port;
        this.user = user == null || user.isEmpty() ? "android" : user;
        this.password = password == null ? "" : password;
        this.listener = listener;
    }

    public void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::runLoop, "dts-client");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        writerRunning = false;
        Thread writer = writerThread;
        if (writer != null) {
            writer.interrupt();
        }
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean send(int cmd, byte[] data) {
        if (!writerRunning) {
            return false;
        }
        return outbound.offer(DtsProtocol.buildPacket(cmd, data));
    }

    private void startWriter() {
        writerRunning = true;
        writerThread = new Thread(this::writeLoop, "dts-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void writeLoop() {
        while (running && writerRunning) {
            byte[] packet;
            try {
                packet = outbound.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
                continue;
            }
            if (packet == null) {
                continue;
            }
            Socket current = socket;
            if (current == null || !current.isConnected()) {
                continue;
            }
            synchronized (sendLock) {
                try {
                    OutputStream out = current.getOutputStream();
                    out.write(packet);
                    out.flush();
                } catch (IOException e) {
                    if (running) {
                        listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                }
            }
        }
    }

    public void sendMouse(String button, String event, int x, int y, int delta) {
        String payload = button + "|" + event + "|" + x + "|" + y + "|" + delta;
        send(DtsProtocol.CMD_MOUSE, payload.getBytes(StandardCharsets.UTF_8));
    }

    public void sendKey(boolean down, int vk) {
        String payload = (down ? "KeyDown" : "KeyUp") + "|" + vk;
        send(DtsProtocol.CMD_KEYBOARD, payload.getBytes(StandardCharsets.UTF_8));
    }

    public void requestFull() {
        send(DtsProtocol.CMD_REQUEST_FULL, "A2".getBytes(StandardCharsets.US_ASCII));
    }

    public void requestControl(String controlPassword) {
        if (controlPassword == null || controlPassword.isEmpty()) {
            send(DtsProtocol.CMD_APPLY_CONTROL, new byte[]{1});
        } else {
            send(DtsProtocol.CMD_APPLY_CONTROL, controlPassword.getBytes(StandardCharsets.UTF_8));
        }
    }

    public void releaseControl() {
        send(DtsProtocol.CMD_APPLY_CONTROL, new byte[]{0});
    }

    private void runLoop() {
        String reason = "disconnected";
        try {
            Socket client = new Socket();
            socket = client;
            client.connect(new InetSocketAddress(host, port), 8000);
            client.setSoTimeout(500);
            client.setTcpNoDelay(true);
            sendLogin();
            startWriter();
            listener.onConnected();
            DtsProtocol.StreamParser parser = new DtsProtocol.StreamParser();
            InputStream in = client.getInputStream();
            byte[] chunk = new byte[65536];
            while (running) {
                int read;
                try {
                    read = in.read(chunk);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                if (read < 0) {
                    reason = "connection closed";
                    break;
                }
                List<DtsProtocol.Frame> frames = parser.feed(chunk, read);
                for (DtsProtocol.Frame frame : frames) {
                    if (!handle(frame)) {
                        reason = "server closed session";
                        running = false;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            reason = e.getMessage() == null ? e.toString() : e.getMessage();
            if (running) {
                listener.onError(reason);
            }
        } finally {
            running = false;
            writerRunning = false;
            Thread writer = writerThread;
            if (writer != null) {
                writer.interrupt();
            }
            Socket client = socket;
            socket = null;
            if (client != null) {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
            if (disconnectNotified.compareAndSet(false, true)) {
                listener.onDisconnected(reason);
            }
        }
    }

    private void sendLogin() throws IOException {
        byte[] userBytes = user.getBytes(StandardCharsets.UTF_8);
        byte[] login;
        if (password.isEmpty()) {
            login = userBytes;
        } else {
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            login = new byte[userBytes.length + 1 + passwordBytes.length];
            System.arraycopy(userBytes, 0, login, 0, userBytes.length);
            login[userBytes.length] = 1;
            System.arraycopy(passwordBytes, 0, login, userBytes.length + 1, passwordBytes.length);
        }
        sendRaw(DtsProtocol.CMD_LOGIN, login);
        sendRaw(DtsProtocol.CMD_VERSION, DtsProtocol.CLIENT_VERSION.getBytes(StandardCharsets.US_ASCII));
    }

    private void sendRaw(int cmd, byte[] data) throws IOException {
        Socket current = socket;
        if (current == null) {
            throw new IOException("not connected");
        }
        synchronized (sendLock) {
            OutputStream out = current.getOutputStream();
            out.write(DtsProtocol.buildPacket(cmd, data));
            out.flush();
        }
    }

    private boolean handle(DtsProtocol.Frame frame) {
        byte[] data = frame.data;
        switch (frame.cmd) {
            case DtsProtocol.CMD_SCREEN_SIZE: {
                String[] parts = DtsProtocol.utf8(data).split(";");
                if (parts.length >= 2) {
                    try {
                        listener.onScreenSize(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (!requestedFull) {
                    requestedFull = true;
                    requestFull();
                }
                return true;
            }
            case DtsProtocol.CMD_TILE_SIZE: {
                String[] parts = DtsProtocol.utf8(data).split(";");
                if (parts.length >= 2) {
                    try {
                        listener.onTileSize(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                return true;
            }
            case DtsProtocol.CMD_FULL_IMAGE:
                listener.onFullImage(data);
                return true;
            case DtsProtocol.CMD_TILE:
                if (data.length >= 3) {
                    byte[] jpeg = new byte[data.length - 2];
                    System.arraycopy(data, 2, jpeg, 0, jpeg.length);
                    listener.onTile(data[0] & 0xFF, data[1] & 0xFF, jpeg);
                }
                return true;
            case DtsProtocol.CMD_TILES_SAME: {
                if (data.length < 2) {
                    return true;
                }
                int count = data[0] & 0xFF;
                byte[] jpeg = new byte[Math.max(0, data.length - 1 - count)];
                System.arraycopy(data, 1 + count, jpeg, 0, jpeg.length);
                int[] xs = new int[count / 2];
                int[] ys = new int[count / 2];
                for (int i = 0; i < xs.length; i++) {
                    xs[i] = data[1 + i * 2] & 0xFF;
                    ys[i] = data[2 + i * 2] & 0xFF;
                }
                listener.onTilesSame(xs, ys, jpeg);
                return true;
            }
            case DtsProtocol.CMD_TILES_BATCH: {
                if (data.length < 2) {
                    return true;
                }
                int count = data[0] & 0xFF;
                int coordBytes = count;
                if (1 + coordBytes > data.length) {
                    return true;
                }
                byte[] blob = new byte[data.length - 1 - coordBytes];
                System.arraycopy(data, 1 + coordBytes, blob, 0, blob.length);
                List<byte[]> parts = DtsProtocol.splitTiles(blob);
                int tiles = count / 2;
                Tile[] result = new Tile[Math.min(tiles, parts.size())];
                for (int i = 0; i < result.length; i++) {
                    int x = data[1 + i * 2] & 0xFF;
                    int y = data[2 + i * 2] & 0xFF;
                    result[i] = new Tile(x, y, parts.get(i));
                }
                listener.onTilesBatch(result);
                return true;
            }
            case DtsProtocol.CMD_KEEPALIVE: {
                DtsProtocol.Frame frameKeepAlive = frame;
                send(DtsProtocol.CMD_TIME_SYNC, frameKeepAlive.data);
                return true;
            }
            case DtsProtocol.CMD_DELAY:
                if (data.length >= 8) {
                    listener.onDelay(DtsProtocol.littleDouble(data, 0));
                }
                return true;
            case DtsProtocol.CMD_ASK_PASSWORD:
                listener.onPasswordRequired();
                return false;
            case DtsProtocol.CMD_BAD_PASSWORD:
                listener.onBadPassword();
                return false;
            case DtsProtocol.CMD_CONTROL_STATE:
                listener.onControlState(data.length > 0 && data[0] == 1);
                return true;
            case DtsProtocol.CMD_WEB_PATH:
                listener.onWebPath(DtsProtocol.utf8(data));
                return true;
            case DtsProtocol.CMD_SERVER_COMMAND:
                listener.onServerCommand(DtsProtocol.utf8(data));
                return true;
            case DtsProtocol.CMD_AUDIO_MP3:
            case DtsProtocol.CMD_AUDIO_SPEEX:
                return true;
            default:
                return true;
        }
    }
}
