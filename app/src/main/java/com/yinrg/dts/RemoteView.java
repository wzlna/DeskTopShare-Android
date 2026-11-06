package com.yinrg.dts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class RemoteView extends View {
    public interface InputSink {
        void mouse(String button, String event, int x, int y, int delta);

        void key(boolean down, int vk);

        void controlNeeded();
    }

    private static final long MAX_CANVAS_PIXELS = 20_000_000L;
    private static final int LONG_PRESS_MS = 550;
    private static final int DOUBLE_TAP_MS = 260;
    private static final ExecutorService TILE_DECODER = Executors.newFixedThreadPool(
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors())),
            runnable -> {
                Thread thread = new Thread(runnable, "dts-decode");
                thread.setDaemon(true);
                return thread;
            });

    private final Object lock = new Object();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;

    private Bitmap base;
    private final SparseArray<Bitmap> tiles = new SparseArray<>();
    private int tileColumns;
    private int tileRows;
    private int remoteWidth;
    private int remoteHeight;
    private int tileWidth = 150;
    private int tileHeight = 150;

    private float drawScale = 1f;
    private float drawLeft;
    private float drawTop;

    private InputSink sink;
    private boolean controlAllowed;

    private float downX;
    private float downY;
    private boolean dragging;
    private boolean longPressFired;
    private boolean multiTouch;
    private float lastMouseX;
    private float lastMouseY;
    private long lastMoveTime;

    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;
    private boolean pendingDouble;

    private float scrollLastY;
    private float scrollAccum;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (sink == null || !controlAllowed || dragging || multiTouch || longPressFired) {
                return;
            }
            int[] point = mapPoint(downX, downY);
            if (point == null) {
                return;
            }
            longPressFired = true;
            sink.mouse("RIGHT", "MouseClick", point[0], point[1], 0);
        }
    };

    public RemoteView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.BLACK);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        textPaint.setColor(Color.rgb(190, 195, 205));
        textPaint.setTextSize(34f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setInputSink(InputSink sink) {
        this.sink = sink;
    }

    public void setControlAllowed(boolean allowed) {
        controlAllowed = allowed;
        if (!allowed) {
            cancelLongPressCallback();
            if (dragging && sink != null) {
                int[] point = mapPoint(lastMouseX, lastMouseY);
                if (point != null) {
                    sink.mouse("LEFT", "MouseUp", point[0], point[1], 0);
                }
            }
            dragging = false;
            longPressFired = false;
        }
    }

    public void setTileSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        synchronized (lock) {
            if (tileWidth != width || tileHeight != height) {
                tileWidth = width;
                tileHeight = height;
                clearTilesLocked();
                updateGridLocked();
            }
        }
        postInvalidateOnAnimation();
    }

    public void clear() {
        synchronized (lock) {
            recycleBaseLocked();
            clearTilesLocked();
            remoteWidth = 0;
            remoteHeight = 0;
            tileColumns = 0;
            tileRows = 0;
        }
        postInvalidateOnAnimation();
    }

    public void setRemoteSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        synchronized (lock) {
            if (remoteWidth != width || remoteHeight != height) {
                remoteWidth = width;
                remoteHeight = height;
                recycleBaseLocked();
                clearTilesLocked();
                updateGridLocked();
            }
        }
        postInvalidateOnAnimation();
    }

    public void showFullJpeg(byte[] jpeg) {
        Bitmap image = decode(jpeg, Bitmap.Config.ARGB_8888);
        if (image == null) {
            return;
        }
        synchronized (lock) {
            int targetWidth = remoteWidth > 0 ? remoteWidth : image.getWidth();
            int targetHeight = remoteHeight > 0 ? remoteHeight : image.getHeight();
            if ((long) targetWidth * targetHeight > MAX_CANVAS_PIXELS) {
                targetWidth = image.getWidth();
                targetHeight = image.getHeight();
            }
            remoteWidth = targetWidth;
            remoteHeight = targetHeight;
            updateGridLocked();
            Bitmap next;
            if (image.getWidth() == targetWidth && image.getHeight() == targetHeight) {
                next = image;
            } else {
                next = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
                Canvas painter = new Canvas(next);
                painter.drawBitmap(image, null, new Rect(0, 0, targetWidth, targetHeight), bitmapPaint);
                image.recycle();
            }
            recycleBaseLocked();
            clearTilesLocked();
            base = next;
        }
        postInvalidateOnAnimation();
    }

    public void showTileJpeg(int x, int y, byte[] jpeg) {
        Bitmap image = decode(jpeg, Bitmap.Config.RGB_565);
        if (image == null) {
            return;
        }
        synchronized (lock) {
            putTileLocked(x, y, image);
        }
        postInvalidateOnAnimation();
    }

    public void showTilesSameJpeg(int[] xs, int[] ys, byte[] jpeg) {
        Bitmap image = decode(jpeg, Bitmap.Config.RGB_565);
        if (image == null) {
            return;
        }
        synchronized (lock) {
            for (int i = 0; i < xs.length && i < ys.length; i++) {
                Bitmap copy = image.copy(Bitmap.Config.RGB_565, false);
                if (copy != null) {
                    putTileLocked(xs[i], ys[i], copy);
                }
            }
        }
        image.recycle();
        postInvalidateOnAnimation();
    }

    public void showTilesBatchJpeg(int[] xs, int[] ys, List<byte[]> jpegs) {
        int count = Math.min(Math.min(xs.length, ys.length), jpegs.size());
        if (count <= 0) {
            return;
        }
        List<Callable<Bitmap>> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final byte[] data = jpegs.get(i);
            tasks.add(() -> decode(data, Bitmap.Config.RGB_565));
        }
        List<Future<Bitmap>> results;
        try {
            results = TILE_DECODER.invokeAll(tasks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                try {
                    Bitmap image = results.get(i).get();
                    if (image != null) {
                        putTileLocked(xs[i], ys[i], image);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        postInvalidateOnAnimation();
    }

    private Bitmap decode(byte[] jpeg, Bitmap.Config config) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = config;
        try {
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        } catch (OutOfMemoryError error) {
            return null;
        }
    }

    private void putTileLocked(int x, int y, Bitmap image) {
        if (remoteWidth <= 0 || remoteHeight <= 0 || tileColumns <= 0 || tileRows <= 0) {
            image.recycle();
            return;
        }
        if (x < 0 || y < 0 || x >= tileColumns || y >= tileRows) {
            image.recycle();
            return;
        }
        int key = y * tileColumns + x;
        Bitmap old = tiles.get(key);
        if (old != null && old != image) {
            old.recycle();
        }
        tiles.put(key, image);
    }

    private void recycleBaseLocked() {
        if (base != null) {
            base.recycle();
            base = null;
        }
    }

    private void clearTilesLocked() {
        for (int i = 0; i < tiles.size(); i++) {
            Bitmap bitmap = tiles.valueAt(i);
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        tiles.clear();
    }

    private void updateGridLocked() {
        if (remoteWidth <= 0 || remoteHeight <= 0 || tileWidth <= 0 || tileHeight <= 0) {
            tileColumns = 0;
            tileRows = 0;
            return;
        }
        tileColumns = (remoteWidth + tileWidth - 1) / tileWidth;
        tileRows = (remoteHeight + tileHeight - 1) / tileHeight;
    }

    private int[] mapPoint(float viewX, float viewY) {
        synchronized (lock) {
            if (remoteWidth <= 0 || remoteHeight <= 0 || drawScale <= 0f) {
                return null;
            }
            int x = (int) ((viewX - drawLeft) / drawScale);
            int y = (int) ((viewY - drawTop) / drawScale);
            if (x < 0 || y < 0 || x >= remoteWidth || y >= remoteHeight) {
                return null;
            }
            return new int[]{x, y};
        }
    }

    private void cancelLongPressCallback() {
        handler.removeCallbacks(longPressRunnable);
    }

    @Override
    protected void onDraw(Canvas painter) {
        super.onDraw(painter);
        synchronized (lock) {
            if (remoteWidth <= 0 || remoteHeight <= 0) {
                painter.drawText("等待画面...", getWidth() / 2f, getHeight() / 2f, textPaint);
                return;
            }
            float scale = Math.min(getWidth() / (float) remoteWidth,
                    getHeight() / (float) remoteHeight);
            drawScale = scale;
            drawLeft = (getWidth() - remoteWidth * scale) / 2f;
            drawTop = (getHeight() - remoteHeight * scale) / 2f;
            painter.save();
            painter.clipRect(drawLeft, drawTop,
                    drawLeft + remoteWidth * scale,
                    drawTop + remoteHeight * scale);
            if (base != null && !base.isRecycled()) {
                RectF destination = new RectF(drawLeft, drawTop,
                        drawLeft + remoteWidth * scale,
                        drawTop + remoteHeight * scale);
                painter.drawBitmap(base, null, destination, bitmapPaint);
            }
            if (tileColumns > 0) {
                RectF destination = new RectF();
                for (int i = 0; i < tiles.size(); i++) {
                    Bitmap bitmap = tiles.valueAt(i);
                    if (bitmap == null || bitmap.isRecycled()) {
                        continue;
                    }
                    int key = tiles.keyAt(i);
                    int tx = key % tileColumns;
                    int ty = key / tileColumns;
                    float left = drawLeft + tx * tileWidth * scale;
                    float top = drawTop + ty * tileHeight * scale;
                    destination.set(left, top,
                            left + tileWidth * scale,
                            top + tileHeight * scale);
                    painter.drawBitmap(bitmap, null, destination, bitmapPaint);
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (sink == null) {
            return false;
        }
        if (!controlAllowed) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                sink.controlNeeded();
            }
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                multiTouch = false;
                dragging = false;
                longPressFired = false;
                downX = event.getX();
                downY = event.getY();
                lastMouseX = downX;
                lastMouseY = downY;
                lastMoveTime = 0;
                long now = System.currentTimeMillis();
                boolean nearLast = Math.abs(downX - lastTapX) < touchSlop * 3
                        && Math.abs(downY - lastTapY) < touchSlop * 3;
                if (now - lastTapTime < DOUBLE_TAP_MS && nearLast) {
                    pendingDouble = true;
                } else {
                    pendingDouble = false;
                }
                cancelLongPressCallback();
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (event.getPointerCount() >= 2) {
                    multiTouch = true;
                    cancelLongPressCallback();
                    if (dragging && sink != null) {
                        int[] point = mapPoint(lastMouseX, lastMouseY);
                        if (point != null) {
                            sink.mouse("LEFT", "MouseUp", point[0], point[1], 0);
                        }
                        dragging = false;
                    }
                    scrollLastY = averageY(event);
                    scrollAccum = 0f;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (multiTouch || event.getPointerCount() >= 2) {
                    float y = averageY(event);
                    scrollAccum += y - scrollLastY;
                    scrollLastY = y;
                    if (Math.abs(scrollAccum) >= 12f) {
                        int wheel = (int) (scrollAccum * 2f);
                        int[] point = mapPoint(event.getX(0), event.getY(0));
                        if (point != null) {
                            sink.mouse("NONE", "MouseWheel", point[0], point[1], wheel);
                        }
                        scrollAccum = 0f;
                    }
                    return true;
                }
                float x = event.getX();
                float y = event.getY();
                float dx = x - downX;
                float dy = y - downY;
                if (!dragging && !longPressFired && dx * dx + dy * dy > touchSlop * touchSlop) {
                    cancelLongPressCallback();
                    int[] point = mapPoint(x, y);
                    if (point == null) {
                        return true;
                    }
                    sink.mouse("LEFT", "MouseDown", point[0], point[1], 0);
                    dragging = true;
                    lastMouseX = x;
                    lastMouseY = y;
                    lastMoveTime = System.currentTimeMillis();
                } else if (dragging) {
                    long now = System.currentTimeMillis();
                    if (now - lastMoveTime >= 55) {
                        int[] point = mapPoint(x, y);
                        if (point != null) {
                            sink.mouse("LEFT", "MouseMove-o", point[0], point[1], 0);
                            lastMouseX = x;
                            lastMouseY = y;
                        }
                        lastMoveTime = now;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                return true;
            }
            case MotionEvent.ACTION_UP: {
                cancelLongPressCallback();
                if (multiTouch) {
                    multiTouch = false;
                    return true;
                }
                if (longPressFired) {
                    longPressFired = false;
                    pendingDouble = false;
                    return true;
                }
                int[] point = mapPoint(event.getX(), event.getY());
                if (point == null) {
                    dragging = false;
                    return true;
                }
                if (dragging) {
                    sink.mouse("LEFT", "MouseUp", point[0], point[1], 0);
                    dragging = false;
                    pendingDouble = false;
                    return true;
                }
                if (pendingDouble) {
                    sink.mouse("LEFT", "MouseDoubleClick", point[0], point[1], 0);
                    pendingDouble = false;
                    lastTapTime = 0;
                } else {
                    lastTapTime = System.currentTimeMillis();
                    lastTapX = event.getX();
                    lastTapY = event.getY();
                    sink.mouse("LEFT", "MouseClick", point[0], point[1], 0);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelLongPressCallback();
                if (dragging && sink != null) {
                    int[] point = mapPoint(lastMouseX, lastMouseY);
                    if (point != null) {
                        sink.mouse("LEFT", "MouseUp", point[0], point[1], 0);
                    }
                }
                dragging = false;
                multiTouch = false;
                longPressFired = false;
                pendingDouble = false;
                return true;
            }
            default:
                return true;
        }
    }

    private float averageY(MotionEvent event) {
        if (event.getPointerCount() == 0) {
            return 0f;
        }
        float sum = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) {
            sum += event.getY(i);
        }
        return sum / event.getPointerCount();
    }

    public void sendKeyTap(int vk) {
        if (sink != null && controlAllowed) {
            sink.key(true, vk);
            sink.key(false, vk);
        }
    }

    public void showKeyboard() {
        requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                sendText(text == null ? "" : text.toString());
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) {
                    sendKeyTap(0x08);
                }
                for (int i = 0; i < afterLength; i++) {
                    sendKeyTap(0x2E);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                sendAndroidKey(event);
                return true;
            }
        };
    }

    private void sendText(String text) {
        if (!controlAllowed) {
            if (sink != null) {
                sink.controlNeeded();
            }
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int[] mapped = mapChar(ch);
            if (mapped == null) {
                continue;
            }
            int vk = mapped[0];
            boolean shift = mapped[1] != 0;
            if (shift) {
                sink.key(true, 0xA0);
            }
            sink.key(true, vk);
            sink.key(false, vk);
            if (shift) {
                sink.key(false, 0xA0);
            }
        }
    }

    private int[] mapChar(char ch) {
        if (ch >= 'a' && ch <= 'z') {
            return new int[]{0x41 + (ch - 'a'), 0};
        }
        if (ch >= 'A' && ch <= 'Z') {
            return new int[]{0x41 + (ch - 'A'), 1};
        }
        if (ch >= '0' && ch <= '9') {
            return new int[]{0x30 + (ch - '0'), 0};
        }
        switch (ch) {
            case ' ':
                return new int[]{0x20, 0};
            case '\n':
                return new int[]{0x0D, 0};
            case '\t':
                return new int[]{0x09, 0};
            case '\b':
                return new int[]{0x08, 0};
            case '-':
                return new int[]{0xBD, 0};
            case '_':
                return new int[]{0xBD, 1};
            case '=':
                return new int[]{0xBB, 0};
            case '+':
                return new int[]{0xBB, 1};
            case '[':
                return new int[]{0xDB, 0};
            case '{':
                return new int[]{0xDB, 1};
            case ']':
                return new int[]{0xDD, 0};
            case '}':
                return new int[]{0xDD, 1};
            case '\\':
                return new int[]{0xDC, 0};
            case '|':
                return new int[]{0xDC, 1};
            case ';':
                return new int[]{0xBA, 0};
            case ':':
                return new int[]{0xBA, 1};
            case '\'':
                return new int[]{0xDE, 0};
            case '"':
                return new int[]{0xDE, 1};
            case ',':
                return new int[]{0xBC, 0};
            case '<':
                return new int[]{0xBC, 1};
            case '.':
                return new int[]{0xBE, 0};
            case '>':
                return new int[]{0xBE, 1};
            case '/':
                return new int[]{0xBF, 0};
            case '?':
                return new int[]{0xBF, 1};
            case '`':
                return new int[]{0xC0, 0};
            case '~':
                return new int[]{0xC0, 1};
            case '!':
                return new int[]{0x31, 1};
            case '@':
                return new int[]{0x32, 1};
            case '#':
                return new int[]{0x33, 1};
            case '$':
                return new int[]{0x34, 1};
            case '%':
                return new int[]{0x35, 1};
            case '^':
                return new int[]{0x36, 1};
            case '&':
                return new int[]{0x37, 1};
            case '*':
                return new int[]{0x38, 1};
            case '(':
                return new int[]{0x39, 1};
            case ')':
                return new int[]{0x30, 1};
            default:
                return null;
        }
    }

    private void sendAndroidKey(KeyEvent event) {
        int vk = androidKeyToVk(event.getKeyCode());
        if (vk < 0 || sink == null || !controlAllowed) {
            return;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            sink.key(true, vk);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            sink.key(false, vk);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int vk = androidKeyToVk(keyCode);
        if (vk < 0) {
            return super.onKeyDown(keyCode, event);
        }
        if (!controlAllowed) {
            if (sink != null) {
                sink.controlNeeded();
            }
            return true;
        }
        if (sink != null && event.getRepeatCount() == 0) {
            sink.key(true, vk);
        }
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int vk = androidKeyToVk(keyCode);
        if (vk < 0) {
            return super.onKeyUp(keyCode, event);
        }
        if (controlAllowed && sink != null) {
            sink.key(false, vk);
        }
        return true;
    }

    private int androidKeyToVk(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return 0x41 + (keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return 0x30 + (keyCode - KeyEvent.KEYCODE_0);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                return 0x08;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return 0x2E;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return 0x0D;
            case KeyEvent.KEYCODE_TAB:
                return 0x09;
            case KeyEvent.KEYCODE_ESCAPE:
                return 0x1B;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 0x25;
            case KeyEvent.KEYCODE_DPAD_UP:
                return 0x26;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 0x27;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 0x28;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return 0x24;
            case KeyEvent.KEYCODE_MOVE_END:
                return 0x23;
            case KeyEvent.KEYCODE_PAGE_UP:
                return 0x21;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return 0x22;
            case KeyEvent.KEYCODE_SPACE:
                return 0x20;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return 0xA0;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return 0xA2;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return 0xA4;
            case KeyEvent.KEYCODE_COMMA:
                return 0xBC;
            case KeyEvent.KEYCODE_PERIOD:
                return 0xBE;
            case KeyEvent.KEYCODE_SLASH:
                return 0xBF;
            case KeyEvent.KEYCODE_SEMICOLON:
                return 0xBA;
            case KeyEvent.KEYCODE_APOSTROPHE:
                return 0xDE;
            case KeyEvent.KEYCODE_LEFT_BRACKET:
                return 0xDB;
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
                return 0xDD;
            case KeyEvent.KEYCODE_BACKSLASH:
                return 0xDC;
            case KeyEvent.KEYCODE_MINUS:
                return 0xBD;
            case KeyEvent.KEYCODE_EQUALS:
                return 0xBB;
            case KeyEvent.KEYCODE_GRAVE:
                return 0xC0;
            default:
                return -1;
        }
    }
}
