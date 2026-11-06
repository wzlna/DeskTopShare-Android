package com.yinrg.dts;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DtsProtocol {
    public static final byte[] MAGIC = new byte[]{0x14, (byte) 0xAA};
    public static final byte[] TILE_SEPARATOR = new byte[]{
            (byte) 0xDE, 0x01, (byte) 0xDD, 0x02, (byte) 0xDC, 0x03
    };

    public static final int CMD_SCREEN_SIZE = 0x11;
    public static final int CMD_FULL_IMAGE = 0x12;
    public static final int CMD_TILE = 0x13;
    public static final int CMD_KEEPALIVE = 0x15;
    public static final int CMD_DELAY = 0x16;
    public static final int CMD_ASK_PASSWORD = 0x17;
    public static final int CMD_BAD_PASSWORD = 0x18;
    public static final int CMD_CONTROL_STATE = 0x19;
    public static final int CMD_TILE_SIZE = 0x20;
    public static final int CMD_TILES_SAME = 0x21;
    public static final int CMD_TILES_BATCH = 0x22;
    public static final int CMD_SERVER_COMMAND = 0x23;
    public static final int CMD_AUDIO_SPEEX = 0x24;
    public static final int CMD_AUDIO_MP3 = 0x25;
    public static final int CMD_WEB_PATH = 0x26;

    public static final int CMD_LOGIN = 0xA1;
    public static final int CMD_REQUEST_FULL = 0xA2;
    public static final int CMD_TIME_SYNC = 0xA4;
    public static final int CMD_MOUSE = 0xA5;
    public static final int CMD_KEYBOARD = 0xA6;
    public static final int CMD_VERSION = 0xA8;
    public static final int CMD_APPLY_CONTROL = 0xA9;

    public static final String CLIENT_VERSION = "2822";

    private DtsProtocol() {
    }

    public static byte[] buildPacket(int cmd, byte[] data) {
        if (data == null) {
            data = new byte[0];
        }
        int bodyLength = 8 + 1 + data.length;
        byte[] packet = new byte[2 + bodyLength + 2];
        packet[0] = MAGIC[0];
        packet[1] = MAGIC[1];
        long length = data.length + 3L;
        for (int i = 0; i < 8; i++) {
            packet[2 + i] = (byte) ((length >>> (8 * i)) & 0xFF);
        }
        packet[10] = (byte) cmd;
        System.arraycopy(data, 0, packet, 11, data.length);
        int sum = 0;
        for (int i = 2; i < 11 + data.length; i++) {
            sum += packet[i] & 0xFF;
        }
        packet[packet.length - 2] = (byte) ((sum >>> 8) & 0xFF);
        packet[packet.length - 1] = (byte) (sum & 0xFF);
        return packet;
    }

    public static final class Frame {
        public final int cmd;
        public final byte[] data;

        public Frame(int cmd, byte[] data) {
            this.cmd = cmd;
            this.data = data;
        }
    }

    public static final class StreamParser {
        private byte[] buffer = new byte[16384];
        private int size;

        public List<Frame> feed(byte[] chunk, int length) {
            if (length > 0) {
                ensure(size + length);
                System.arraycopy(chunk, 0, buffer, size, length);
                size += length;
            }
            List<Frame> frames = new ArrayList<>();
            while (true) {
                int start = findMagic();
                if (start < 0) {
                    if (size >= 2) {
                        buffer[0] = buffer[size - 1];
                        size = 1;
                    }
                    break;
                }
                if (start > 0) {
                    System.arraycopy(buffer, start, buffer, 0, size - start);
                    size -= start;
                }
                if (size < 10) {
                    break;
                }
                long bodyLength = 0;
                for (int i = 0; i < 8; i++) {
                    bodyLength |= (long) (buffer[2 + i] & 0xFF) << (8 * i);
                }
                if (bodyLength < 3 || bodyLength > 64L * 1024 * 1024) {
                    System.arraycopy(buffer, 2, buffer, 0, size - 2);
                    size -= 2;
                    continue;
                }
                int total = (int) (10 + bodyLength);
                if (size < total) {
                    break;
                }
                int sum = 0;
                for (int i = 2; i < total - 2; i++) {
                    sum += buffer[i] & 0xFF;
                }
                int expected = ((buffer[total - 2] & 0xFF) << 8) | (buffer[total - 1] & 0xFF);
                if ((sum & 0xFFFF) == expected && bodyLength >= 3) {
                    int cmd = buffer[10] & 0xFF;
                    int dataLength = (int) bodyLength - 3;
                    byte[] data = new byte[dataLength];
                    System.arraycopy(buffer, 11, data, 0, dataLength);
                    frames.add(new Frame(cmd, data));
                }
                int remaining = size - total;
                if (remaining > 0) {
                    System.arraycopy(buffer, total, buffer, 0, remaining);
                }
                size = remaining;
            }
            return frames;
        }

        private int findMagic() {
            for (int i = 0; i + 1 < size; i++) {
                if (buffer[i] == MAGIC[0] && buffer[i + 1] == MAGIC[1]) {
                    return i;
                }
            }
            return -1;
        }

        private void ensure(int needed) {
            if (needed <= buffer.length) {
                return;
            }
            int newSize = buffer.length;
            while (newSize < needed) {
                newSize *= 2;
            }
            byte[] next = new byte[newSize];
            System.arraycopy(buffer, 0, next, 0, size);
            buffer = next;
        }
    }

    public static String utf8(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    public static double littleDouble(byte[] data, int offset) {
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits |= (long) (data[offset + i] & 0xFF) << (8 * i);
        }
        return Double.longBitsToDouble(bits);
    }

    public static List<byte[]> splitTiles(byte[] data) {
        List<byte[]> parts = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i <= data.length - TILE_SEPARATOR.length) {
            boolean match = true;
            for (int j = 0; j < TILE_SEPARATOR.length; j++) {
                if (data[i + j] != TILE_SEPARATOR[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                byte[] part = new byte[i - start];
                System.arraycopy(data, start, part, 0, part.length);
                parts.add(part);
                i += TILE_SEPARATOR.length;
                start = i;
            } else {
                i++;
            }
        }
        byte[] tail = new byte[data.length - start];
        System.arraycopy(data, start, tail, 0, tail.length);
        parts.add(tail);
        return parts;
    }

    public static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static String hex(byte value) {
        return String.format("%02X", value & 0xFF);
    }

    public static ByteArrayOutputStream newBuffer() {
        return new ByteArrayOutputStream();
    }
}
