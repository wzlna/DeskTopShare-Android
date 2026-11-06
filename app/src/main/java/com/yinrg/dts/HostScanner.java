package com.yinrg.dts;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class HostScanner {
    public interface Listener {
        void onFound(String ip, String hostName);

        void onProgress(String message);

        void onError(String message);

        void onFinished(int count);
    }

    private static final int PORT = 8990;
    private static final byte[] PROBE = new byte[]{1};

    private volatile boolean running;
    private volatile DatagramSocket socket;
    private Thread thread;
    private final String subnet;
    private final Listener listener;

    public HostScanner(String subnet, Listener listener) {
        this.subnet = subnet == null ? "" : subnet.trim();
        this.listener = listener;
    }

    public void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::runScan, "dts-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        DatagramSocket current = socket;
        socket = null;
        if (current != null) {
            current.close();
        }
    }

    public static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.getHostAddress().startsWith("169.254.")) {
                        result.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static String defaultSubnet() {
        for (String address : localIpv4Addresses()) {
            int dot = address.lastIndexOf('.');
            if (dot > 0) {
                return address.substring(0, dot) + ".0/24";
            }
        }
        return "";
    }

    private void runScan() {
        int found = 0;
        Set<String> seen = new LinkedHashSet<>();
        DatagramSocket scan = null;
        try {
            scan = new DatagramSocket(null);
            scan.setReuseAddress(true);
            scan.setBroadcast(true);
            scan.bind(new InetSocketAddress(PORT));
            scan.setSoTimeout(200);
            socket = scan;
            listener.onProgress("正在发送 UDP 8990 发现包...");

            List<String> broadcasts = new ArrayList<>();
            broadcasts.add("255.255.255.255");
            List<String> hosts = new ArrayList<>();

            if (!subnet.isEmpty()) {
                addSubnet(subnet, hosts, broadcasts);
            } else {
                try {
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        NetworkInterface ni = interfaces.nextElement();
                        if (!ni.isUp() || ni.isLoopback()) {
                            continue;
                        }
                        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                            InetAddress address = ia.getAddress();
                            if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                                continue;
                            }
                            String ip = address.getHostAddress();
                            String prefix = ip.substring(0, ip.lastIndexOf('.'));
                            for (int i = 1; i <= 254; i++) {
                                hosts.add(prefix + "." + i);
                            }
                            InetAddress broadcast = ia.getBroadcast();
                            if (broadcast != null) {
                                broadcasts.add(broadcast.getHostAddress());
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            Set<String> targets = new LinkedHashSet<>();
            targets.addAll(broadcasts);
            targets.addAll(hosts);
            int sent = 0;
            for (String target : targets) {
                if (!running) {
                    break;
                }
                try {
                    InetAddress address = InetAddress.getByName(target);
                    scan.send(new DatagramPacket(PROBE, PROBE.length, address, PORT));
                } catch (Exception ignored) {
                }
                sent++;
                if (sent % 64 == 0) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            long deadline = System.currentTimeMillis() + 3500;
            byte[] buffer = new byte[2048];
            while (running && System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    scan.receive(packet);
                } catch (SocketTimeoutException timeout) {
                    continue;
                } catch (Exception e) {
                    break;
                }
                if (packet.getLength() < 1 || (buffer[0] & 0xFF) != 2) {
                    continue;
                }
                String ip = packet.getAddress().getHostAddress();
                if (!seen.add(ip)) {
                    continue;
                }
                String name = new String(buffer, 1, packet.getLength() - 1, StandardCharsets.UTF_16LE)
                        .replace("\u0000", "");
                found++;
                listener.onFound(ip, name);
            }
        } catch (SocketException e) {
            listener.onError("无法监听 UDP 8990: " + e.getMessage());
        } catch (Exception e) {
            listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            running = false;
            if (scan != null) {
                scan.close();
            }
            socket = null;
            listener.onFinished(found);
        }
    }

    private void addSubnet(String subnet, List<String> hosts, List<String> broadcasts) {
        try {
            String value = subnet;
            int slash = value.indexOf('/');
            int prefixLength = 24;
            if (slash >= 0) {
                prefixLength = Integer.parseInt(value.substring(slash + 1));
                value = value.substring(0, slash);
            }
            if (prefixLength < 8 || prefixLength > 32) {
                listener.onError("无效网段前缀");
                return;
            }
            String[] parts = value.split("\\.");
            if (parts.length != 4) {
                listener.onError("无效网段");
                return;
            }
            long ip = 0;
            for (String part : parts) {
                ip = (ip << 8) | (Integer.parseInt(part) & 0xFF);
            }
            long mask = prefixLength == 0 ? 0 : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
            long network = ip & mask;
            long broadcast = network | (~mask & 0xFFFFFFFFL);
            if (prefixLength >= 31) {
                hosts.add(ipToString(network));
                broadcasts.add(ipToString(broadcast));
                return;
            }
            long count = broadcast - network - 1;
            if (count > 4096) {
                listener.onError("网段太大，最多扫描 4096 个地址");
                return;
            }
            for (long i = network + 1; i < broadcast; i++) {
                hosts.add(ipToString(i));
            }
            broadcasts.add(ipToString(broadcast));
        } catch (Exception e) {
            listener.onError("无效网段");
        }
    }

    private static String ipToString(long value) {
        return ((value >>> 24) & 0xFF) + "." + ((value >>> 16) & 0xFF) + "."
                + ((value >>> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
