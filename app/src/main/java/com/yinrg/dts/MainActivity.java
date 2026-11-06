package com.yinrg.dts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements RemoteView.InputSink {
    private static final int COLOR_BG = Color.rgb(17, 20, 26);
    private static final int COLOR_PANEL = Color.rgb(26, 31, 40);
    private static final int COLOR_TEXT = Color.rgb(232, 236, 242);
    private static final int COLOR_MUTED = Color.rgb(150, 160, 175);
    private static final int COLOR_ACCENT = Color.rgb(64, 124, 255);
    private static final int COLOR_BUTTON = Color.rgb(40, 47, 60);
    private static final int COLOR_BUTTON_ON = Color.rgb(40, 96, 210);

    private SharedPreferences prefs;
    private EditText hostEdit;
    private EditText portEdit;
    private EditText userEdit;
    private EditText passwordEdit;
    private TextView connectStatus;
    private LinearLayout connectPanel;
    private FrameLayout remoteContainer;
    private RemoteView remoteView;
    private LinearLayout topBar;
    private HorizontalScrollView keyBar;
    private TextView stateText;
    private Button controlButton;
    private Button keyboardButton;
    private Button ctrlButton;
    private Button altButton;
    private boolean ctrlOn;
    private boolean altOn;
    private boolean keyboardVisible;

    private volatile DtsClient client;
    private volatile HostScanner scanner;
    private WifiManager.MulticastLock multicastLock;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("dts", MODE_PRIVATE);
        buildUi();
        applyImmersive();
        loadPrefs();
        handleIntent(getIntent());
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BG);

        connectPanel = new LinearLayout(this);
        connectPanel.setOrientation(LinearLayout.VERTICAL);
        connectPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        connectPanel.setPadding(dp(28), dp(28), dp(28), dp(28));
        connectPanel.setBackgroundColor(COLOR_BG);

        TextView title = new TextView(this);
        title.setText("DeskTopShare Android");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        connectPanel.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("平板远程桌面客户端");
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(4);
        subtitleParams.bottomMargin = dp(20);
        connectPanel.addView(subtitle, subtitleParams);

        hostEdit = makeEdit("主机，例如 10.2.26.2", InputType.TYPE_CLASS_TEXT);
        portEdit = makeEdit("端口", InputType.TYPE_CLASS_NUMBER);
        portEdit.setText("8989");
        userEdit = makeEdit("用户名", InputType.TYPE_CLASS_TEXT);
        userEdit.setText("android");
        passwordEdit = makeEdit("查看密码，可留空", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        connectPanel.addView(hostEdit, editParams());
        connectPanel.addView(portEdit, editParams());
        connectPanel.addView(userEdit, editParams());
        connectPanel.addView(passwordEdit, editParams());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button connect = makeActionButton("连接", COLOR_ACCENT);
        Button search = makeActionButton("搜索主机", COLOR_BUTTON);
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        connectParams.rightMargin = dp(8);
        row.addView(connect, connectParams);
        row.addView(search, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(16);
        connectPanel.addView(row, rowParams);

        connectStatus = new TextView(this);
        connectStatus.setTextColor(COLOR_MUTED);
        connectStatus.setTextSize(13);
        connectStatus.setGravity(Gravity.CENTER);
        connectStatus.setText("输入服务端 IP 后连接。UDP 8990 可用于局域网搜索。");
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(16);
        connectPanel.addView(connectStatus, statusParams);

        TextView hint = new TextView(this);
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        hint.setLineSpacing(dp(3), 1f);
        hint.setText("触控：单击左键，长按右键，拖动拖拽，双击双击，双指上下滚动。\n键盘：点“键盘”呼出输入法，支持英文/ASCII。");
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(18);
        connectPanel.addView(hint, hintParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(connectPanel);
        root.addView(scroll, matchMatch());

        remoteContainer = new FrameLayout(this);
        remoteContainer.setBackgroundColor(Color.BLACK);
        remoteContainer.setVisibility(View.GONE);
        remoteView = new RemoteView(this);
        remoteView.setInputSink(this);
        remoteContainer.addView(remoteView, matchMatch());

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        topBar.setBackgroundColor(0xB312171F);

        Button disconnect = makeSmallButton("断开");
        controlButton = makeSmallButton("申请控制");
        keyboardButton = makeSmallButton("键盘");
        Button keysButton = makeSmallButton("按键");
        stateText = new TextView(this);
        stateText.setTextColor(COLOR_MUTED);
        stateText.setTextSize(12);
        stateText.setText("连接中...");
        stateText.setPadding(dp(8), 0, dp(8), 0);
        topBar.addView(disconnect, wrapWrap());
        topBar.addView(controlButton, wrapWrap());
        topBar.addView(keyboardButton, wrapWrap());
        topBar.addView(keysButton, wrapWrap());
        topBar.addView(stateText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        remoteContainer.addView(topBar, topParams);

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setPadding(dp(6), dp(4), dp(6), dp(4));
        keyRow.setBackgroundColor(0xB312171F);
        addKeyButton(keyRow, "Esc", 0x1B);
        addKeyButton(keyRow, "Tab", 0x09);
        ctrlButton = addToggleButton(keyRow, "Ctrl", 0xA2, true);
        altButton = addToggleButton(keyRow, "Alt", 0xA4, false);
        addKeyButton(keyRow, "Win", 0x5B);
        addKeyButton(keyRow, "Enter", 0x0D);
        addKeyButton(keyRow, "⌫", 0x08);
        addKeyButton(keyRow, "Del", 0x2E);
        addKeyButton(keyRow, "←", 0x25);
        addKeyButton(keyRow, "↑", 0x26);
        addKeyButton(keyRow, "↓", 0x28);
        addKeyButton(keyRow, "→", 0x27);
        addKeyButton(keyRow, "F5", 0x74);
        keyBar = new HorizontalScrollView(this);
        keyBar.setHorizontalScrollBarEnabled(false);
        keyBar.setBackgroundColor(0xB312171F);
        keyBar.addView(keyRow);
        keyBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams keyParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        remoteContainer.addView(keyBar, keyParams);

        root.addView(remoteContainer, matchMatch());
        setContentView(root);

        connect.setOnClickListener(v -> connect());
        search.setOnClickListener(v -> showSearchDialog());
        disconnect.setOnClickListener(v -> disconnectToConnect());
        controlButton.setOnClickListener(v -> toggleControl());
        keyboardButton.setOnClickListener(v -> toggleKeyboard());
        keysButton.setOnClickListener(v -> keyBar.setVisibility(
                keyBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String host = intent.getStringExtra("host");
        if (host == null || host.isEmpty()) {
            return;
        }
        hostEdit.setText(host);
        portEdit.setText(String.valueOf(intent.getIntExtra("port", 8989)));
        String user = intent.getStringExtra("user");
        if (user != null) {
            userEdit.setText(user);
        }
        String password = intent.getStringExtra("password");
        if (password != null) {
            passwordEdit.setText(password);
        }
        connectPanel.postDelayed(this::connect, 600);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (remoteContainer != null && remoteContainer.getVisibility() == View.VISIBLE) {
            disconnectToConnect();
        }
        handleIntent(intent);
    }

    private void connect() {
        String host = hostEdit.getText().toString().trim();
        if (host.isEmpty()) {
            connectStatus.setText("请填写主机 IP 或使用搜索主机");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            connectStatus.setText("端口无效");
            return;
        }
        String user = userEdit.getText().toString().trim();
        if (user.isEmpty()) {
            user = "android";
        }
        String password = passwordEdit.getText().toString();
        prefs.edit()
                .putString("host", host)
                .putString("port", String.valueOf(port))
                .putString("user", user)
                .putBoolean("hasPassword", !password.isEmpty())
                .apply();
        stopClient();
        final int currentGeneration = ++generation;
        remoteView.clear();
        remoteView.setControlAllowed(false);
        remoteView.setInputSink(this);
        remoteView.requestFocus();
        connectPanel.setVisibility(View.GONE);
        remoteContainer.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.VISIBLE);
        stateText.setText("连接中 " + host + ":" + port);
        controlButton.setText("申请控制");
        keyboardVisible = false;
        keyboardButton.setText("键盘");

        DtsClient newClient = new DtsClient(host, port, user, password, new DtsClient.Listener() {
            private boolean alive() {
                return currentGeneration == generation;
            }

            @Override
            public void onConnected() {
                if (alive()) {
                    runOnUiThread(() -> stateText.setText("已连接，等待画面..."));
                }
            }

            @Override
            public void onDisconnected(String reason) {
                if (!alive()) {
                    return;
                }
                runOnUiThread(() -> {
                    if (currentGeneration != generation) {
                        return;
                    }
                    client = null;
                    generation++;
                    releaseModifiers();
                    remoteView.setControlAllowed(false);
                    remoteContainer.setVisibility(View.GONE);
                    connectPanel.setVisibility(View.VISIBLE);
                    connectStatus.setText("连接断开: " + reason);
                    controlButton.setText("申请控制");
                });
            }

            @Override
            public void onError(String message) {
                if (alive()) {
                    runOnUiThread(() -> stateText.setText("错误: " + message));
                }
            }

            @Override
            public void onScreenSize(int width, int height) {
                if (!alive()) {
                    return;
                }
                remoteView.setRemoteSize(width, height);
                runOnUiThread(() -> stateText.setText("画面 " + width + "x" + height));
            }

            @Override
            public void onTileSize(int width, int height) {
                if (!alive()) {
                    return;
                }
                remoteView.setTileSize(width, height);
            }

            @Override
            public void onFullImage(byte[] jpeg) {
                if (!alive()) {
                    return;
                }
                remoteView.showFullJpeg(jpeg);
            }

            @Override
            public void onTile(int x, int y, byte[] jpeg) {
                if (!alive()) {
                    return;
                }
                remoteView.showTileJpeg(x, y, jpeg);
            }

            @Override
            public void onTilesSame(int[] xs, int[] ys, byte[] jpeg) {
                if (!alive()) {
                    return;
                }
                remoteView.showTilesSameJpeg(xs, ys, jpeg);
            }

            @Override
            public void onTilesBatch(DtsClient.Tile[] tiles) {
                if (!alive() || tiles == null || tiles.length == 0) {
                    return;
                }
                int[] xs = new int[tiles.length];
                int[] ys = new int[tiles.length];
                List<byte[]> jpegs = new ArrayList<>(tiles.length);
                for (int i = 0; i < tiles.length; i++) {
                    xs[i] = tiles[i].x;
                    ys[i] = tiles[i].y;
                    jpegs.add(tiles[i].jpeg);
                }
                remoteView.showTilesBatchJpeg(xs, ys, jpegs);
            }

            @Override
            public void onDelay(double seconds) {
                if (alive()) {
                    runOnUiThread(() -> stateText.setText(
                            String.format("延迟 %.3fs", seconds)));
                }
            }

            @Override
            public void onPasswordRequired() {
                if (!alive()) {
                    return;
                }
                runOnUiThread(() -> {
                    if (currentGeneration != generation) {
                        return;
                    }
                    showPasswordDialog();
                });
            }

            @Override
            public void onBadPassword() {
                if (!alive()) {
                    return;
                }
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "密码错误", Toast.LENGTH_SHORT).show();
                    disconnectToConnect();
                    connectStatus.setText("密码错误");
                });
            }

            @Override
            public void onControlState(boolean allowed) {
                remoteView.setControlAllowed(allowed);
                if (alive()) {
                    runOnUiThread(() -> {
                        controlButton.setText(allowed ? "释放控制" : "申请控制");
                        Toast.makeText(MainActivity.this,
                                allowed ? "已获得控制权限" : "服务端未授权控制",
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onWebPath(String path) {
                if (path != null && !path.isEmpty() && alive()) {
                    runOnUiThread(() -> stateText.setText("远程文档: " + path));
                }
            }

            @Override
            public void onServerCommand(String command) {
                if (command != null && command.startsWith("server-shutdown") && alive()) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            command, Toast.LENGTH_LONG).show());
                }
            }
        });
        client = newClient;
        newClient.start();
    }

    private void showPasswordDialog() {
        final EditText input = makeEdit("查看密码", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("需要密码")
                .setMessage("服务端要求查看密码")
                .setView(input)
                .setPositiveButton("连接", (d, which) -> {
                    passwordEdit.setText(input.getText().toString());
                    connect();
                })
                .setNegativeButton("取消", (d, which) -> disconnectToConnect())
                .create();
        dialog.show();
    }

    private void toggleControl() {
        DtsClient current = client;
        if (current == null) {
            return;
        }
        if (controlButton.getText().toString().startsWith("释放")) {
            current.releaseControl();
        } else {
            current.requestControl(passwordEdit.getText().toString());
            stateText.setText("已发送控制申请，等待服务端同意");
        }
    }

    private void toggleKeyboard() {
        if (keyboardVisible) {
            remoteView.hideKeyboard();
            keyboardVisible = false;
            keyboardButton.setText("键盘");
        } else {
            remoteView.showKeyboard();
            keyboardVisible = true;
            keyboardButton.setText("收起键盘");
        }
    }

    private void disconnectToConnect() {
        stopClient();
        generation++;
        keyboardVisible = false;
        remoteContainer.setVisibility(View.GONE);
        connectPanel.setVisibility(View.VISIBLE);
        connectStatus.setText("已断开");
    }

    private void stopClient() {
        DtsClient current = client;
        if (current != null) {
            releaseModifiers();
        }
        client = null;
        if (current != null) {
            current.stop();
        }
        remoteView.hideKeyboard();
        remoteView.setControlAllowed(false);
    }

    private void loadPrefs() {
        hostEdit.setText(prefs.getString("host", ""));
        portEdit.setText(prefs.getString("port", "8989"));
        userEdit.setText(prefs.getString("user", "android"));
        passwordEdit.setText("");
        if (!hostEdit.getText().toString().isEmpty()) {
            connectStatus.setText("上次连接: " + hostEdit.getText().toString());
        }
    }

    private void addKeyButton(LinearLayout row, String label, int vk) {
        Button button = makeSmallButton(label);
        button.setOnClickListener(v -> remoteView.sendKeyTap(vk));
        row.addView(button, wrapWrap());
    }

    private Button addToggleButton(LinearLayout row, String label, int vk, boolean ctrl) {
        Button button = makeSmallButton(label);
        button.setOnClickListener(v -> {
            boolean next;
            Button target = ctrl ? ctrlButton : altButton;
            boolean current = ctrl ? ctrlOn : altOn;
            next = !current;
            if (ctrl) {
                ctrlOn = next;
            } else {
                altOn = next;
            }
            target.setBackgroundColor(next ? COLOR_BUTTON_ON : COLOR_BUTTON);
            if (client != null) {
                client.sendKey(next, vk);
            }
        });
        row.addView(button, wrapWrap());
        return button;
    }

    private void releaseModifiers() {
        if (client != null) {
            if (ctrlOn) {
                client.sendKey(false, 0xA2);
            }
            if (altOn) {
                client.sendKey(false, 0xA4);
            }
        }
        ctrlOn = false;
        altOn = false;
        if (ctrlButton != null) {
            ctrlButton.setBackgroundColor(COLOR_BUTTON);
        }
        if (altButton != null) {
            altButton.setBackgroundColor(COLOR_BUTTON);
        }
    }

    private void showSearchDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(10), dp(18), 0);

        EditText subnet = makeEdit("留空扫描本机 /24，或输入 10.2.26.0/24", InputType.TYPE_CLASS_TEXT);
        String defaultSubnet = HostScanner.defaultSubnet();
        subnet.setText(defaultSubnet);
        layout.addView(subnet, matchWrap());

        TextView status = new TextView(this);
        status.setTextColor(COLOR_MUTED);
        status.setTextSize(13);
        status.setText("准备扫描...");
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(8);
        layout.addView(status, statusParams);

        ListView list = new ListView(this);
        List<HostEntry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280));
        listParams.topMargin = dp(4);
        layout.addView(list, listParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("搜索主机")
                .setView(layout)
                .setPositiveButton("重新扫描", null)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    startScan(subnet.getText().toString(), status, adapter, entries, labels));
            startScan(subnet.getText().toString(), status, adapter, entries, labels);
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= entries.size()) {
                return;
            }
            HostEntry entry = entries.get(position);
            hostEdit.setText(entry.ip);
            connectStatus.setText("已选择 " + entry.ip + (entry.name.isEmpty() ? "" : " / " + entry.name));
            dialog.dismiss();
        });
        dialog.setOnDismissListener(d -> stopScan());
        dialog.show();
    }

    private void startScan(String subnet, TextView status, ArrayAdapter<String> adapter,
                           List<HostEntry> entries, List<String> labels) {
        stopScan();
        acquireMulticastLock();
        entries.clear();
        labels.clear();
        adapter.notifyDataSetChanged();
        status.setText("正在通过 UDP 8990 搜索...");
        HostScanner scan = new HostScanner(subnet, new HostScanner.Listener() {
            @Override
            public void onFound(String ip, String hostName) {
                runOnUiThread(() -> {
                    for (HostEntry entry : entries) {
                        if (entry.ip.equals(ip)) {
                            return;
                        }
                    }
                    entries.add(new HostEntry(ip, hostName));
                    labels.add(ip + (hostName.isEmpty() ? "" : "   " + hostName));
                    adapter.notifyDataSetChanged();
                    status.setText("已发现 " + entries.size() + " 台主机");
                });
            }

            @Override
            public void onProgress(String message) {
                runOnUiThread(() -> status.setText(message));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> status.setText(message));
            }

            @Override
            public void onFinished(int count) {
                runOnUiThread(() -> status.setText("搜索完成，发现 " + count + " 台主机"));
            }
        });
        scanner = scan;
        scan.start();
    }

    private void stopScan() {
        HostScanner current = scanner;
        scanner = null;
        if (current != null) {
            current.stop();
        }
        releaseMulticastLock();
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("dts-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) {
                    multicastLock.release();
                }
            } catch (Exception ignored) {
            }
            multicastLock = null;
        }
    }

    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopClient();
        stopScan();
    }

    @Override
    public void onBackPressed() {
        if (keyboardVisible) {
            remoteView.hideKeyboard();
            keyboardVisible = false;
            keyboardButton.setText("键盘");
            return;
        }
        if (remoteContainer != null && remoteContainer.getVisibility() == View.VISIBLE) {
            disconnectToConnect();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void mouse(String button, String event, int x, int y, int delta) {
        DtsClient current = client;
        if (current != null) {
            current.sendMouse(button, event, x, y, delta);
        }
    }

    @Override
    public void key(boolean down, int vk) {
        DtsClient current = client;
        if (current != null) {
            current.sendKey(down, vk);
        }
    }

    @Override
    public void controlNeeded() {
        runOnUiThread(() -> stateText.setText("没有控制权限，请先点“申请控制”"));
    }

    private EditText makeEdit(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextColor(COLOR_TEXT);
        edit.setHintTextColor(COLOR_MUTED);
        edit.setTextSize(15);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        edit.setBackgroundColor(COLOR_PANEL);
        return edit;
    }

    private Button makeActionButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setBackgroundColor(color);
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        return button;
    }

    private Button makeSmallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(12);
        button.setBackgroundColor(COLOR_BUTTON);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(4);
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout.LayoutParams editParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        return params;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams matchMatch() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class HostEntry {
        final String ip;
        final String name;

        HostEntry(String ip, String name) {
            this.ip = ip;
            this.name = name == null ? "" : name;
        }
    }
}
