import com.yinrg.dts.DtsClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

public final class Probe {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8989;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        AtomicInteger tiles = new AtomicInteger();
        AtomicInteger batches = new AtomicInteger();
        Path out = Paths.get("jvm-full.jpg");

        DtsClient.Listener listener = new DtsClient.Listener() {
            @Override
            public void onConnected() {
                System.out.println("connected");
            }

            @Override
            public void onDisconnected(String reason) {
                System.out.println("disconnected: " + reason);
            }

            @Override
            public void onError(String message) {
                System.out.println("error: " + message);
            }

            @Override
            public void onScreenSize(int width, int height) {
                System.out.println("screen: " + width + "x" + height);
            }

            @Override
            public void onTileSize(int width, int height) {
                System.out.println("tile: " + width + "x" + height);
            }

            @Override
            public void onFullImage(byte[] jpeg) {
                System.out.println("full image: " + jpeg.length + " bytes");
                try {
                    Files.write(out, jpeg);
                } catch (Exception e) {
                    System.out.println("save error: " + e);
                }
            }

            @Override
            public void onTile(int x, int y, byte[] jpeg) {
                int count = tiles.incrementAndGet();
                if (count <= 5) {
                    System.out.println("tile 0x13: " + x + "," + y + " " + jpeg.length);
                }
            }

            @Override
            public void onTilesBatch(DtsClient.Tile[] batch) {
                batches.incrementAndGet();
                tiles.addAndGet(batch.length);
                if (batches.get() <= 3) {
                    System.out.println("batch 0x22: " + batch.length);
                }
            }

            @Override
            public void onDelay(double delay) {
                System.out.println("delay: " + delay);
            }

            @Override
            public void onControlState(boolean allowed) {
                System.out.println("control: " + allowed);
            }

            @Override
            public void onPasswordRequired() {
                System.out.println("password required");
            }

            @Override
            public void onBadPassword() {
                System.out.println("bad password");
            }
        };

        DtsClient client = new DtsClient(host, port, "java-probe", "", listener);
        client.start();
        Thread.sleep(seconds * 1000L);
        client.stop();
        Thread.sleep(300);
        System.out.println("tiles=" + tiles.get() + " batches=" + batches.get());
        System.out.println("saved: " + out.toAbsolutePath());
    }
}
