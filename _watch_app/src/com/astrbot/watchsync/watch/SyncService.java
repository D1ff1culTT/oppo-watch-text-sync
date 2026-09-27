package com.astrbot.watchsync.watch;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Vibrator;

import com.astrbot.watchsync.common.Proto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 手表端蓝牙客户端同步服务（仅 app 打开期间运行，退出即停，零后台）。
 *
 * 连接策略：从已配对设备里找手机（手机类设备优先），RFCOMM 安全连接优先、
 * 失败退非安全；连上后发 hello + 回放 pending ops，然后收手机的全量 list；
 * 断开后每 10 秒重试。
 */
public class SyncService extends Service {

    public static final String ACTION_STATE = "com.astrbot.watchsync.watch.STATE";
    public static final String ACTION_LIST = "com.astrbot.watchsync.watch.LIST";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_HAS_NEW = "hasNew";

    private static final long RETRY_MS = 10_000;

    public static volatile boolean sConnected = false;

    private volatile Worker worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (this) {
            if (worker == null || !worker.isAlive()) {
                worker = new Worker();
                worker.start();
            }
        }
        // 有新的待回放操作且当前已连接：立刻发送
        if (sConnected) {
            Worker w = worker;
            if (w != null) {
                w.flushPendingOps();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Worker w = worker;
        if (w != null) {
            w.halt();
        }
        sConnected = false;
        super.onDestroy();
    }

    /** UI 调用：手表上做了删除/置顶操作，入队并触发回放。 */
    public static void sendOp(Context ctx, JSONObject op) {
        MsgStore.addPending(ctx, op);
        ctx.startService(new Intent(ctx, SyncService.class));
    }

    // ------------------------------------------------------------------ //
    // 连接工作线程
    // ------------------------------------------------------------------ //
    private class Worker extends Thread {

        private volatile boolean running = true;
        private volatile OutputStream out;
        private volatile BluetoothSocket activeSocket;
        private final Object writeLock = new Object();

        Worker() {
            super("watchsync-worker");
        }

        void halt() {
            running = false;
            closeQuietly(activeSocket);
            interrupt();
        }

        @Override
        public void run() {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            while (running) {
                if (adapter == null || !adapter.isEnabled()) {
                    setState(false, "手表蓝牙未开启");
                    if (!rest()) {
                        break;
                    }
                    continue;
                }
                BluetoothSocket socket = connectAny(adapter);
                if (!running) {
                    closeQuietly(socket);
                    break;
                }
                if (socket == null) {
                    setState(false, "未连上手机（手机端 app 需在运行）");
                    if (!rest()) {
                        break;
                    }
                    continue;
                }
                try {
                    session(socket, adapter);
                } catch (Exception e) {
                    setState(false, "连接断开");
                } finally {
                    closeQuietly(socket);
                }
                if (running && !rest()) {
                    break;
                }
            }
        }

        /** 依次尝试已配对设备；手机类设备排前面。返回第一个连上的 socket。 */
        private BluetoothSocket connectAny(BluetoothAdapter adapter) {
            List<BluetoothDevice> candidates;
            try {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                candidates = bonded == null ? new ArrayList<BluetoothDevice>()
                        : new ArrayList<>(bonded);
            } catch (Exception e) {
                return null;
            }
            Collections.sort(candidates, new Comparator<BluetoothDevice>() {
                @Override
                public int compare(BluetoothDevice a, BluetoothDevice b) {
                    boolean pa = isPhone(a);
                    boolean pb = isPhone(b);
                    if (pa != pb) {
                        return pa ? -1 : 1;
                    }
                    return String.valueOf(a.getAddress())
                            .compareTo(String.valueOf(b.getAddress()));
                }
            });
            for (BluetoothDevice d : candidates) {
                if (!running) {
                    return null;
                }
                // 不显示具体设备名（可能扫到耳机等无关设备），统一口径
                setState(false, "正在连接手机…");
                BluetoothSocket s = connect(d, true);
                if (s == null) {
                    s = connect(d, false); // 未配对/安全通道失败时的兜底
                }
                if (s != null) {
                    return s;
                }
            }
            return null;
        }

        private BluetoothSocket connect(BluetoothDevice d, boolean secure) {
            BluetoothSocket s;
            try {
                s = secure
                        ? d.createRfcommSocketToServiceRecord(UUID.fromString(Proto.SERVICE_UUID))
                        : d.createInsecureRfcommSocketToServiceRecord(UUID.fromString(Proto.SERVICE_UUID));
            } catch (Exception e) {
                return null;
            }
            try {
                s.connect();
                return s;
            } catch (Exception e) {
                closeQuietly(s);
                return null;
            }
        }

        private boolean isPhone(BluetoothDevice d) {
            try {
                BluetoothClass cls = d.getBluetoothClass();
                return cls != null
                        && cls.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
            } catch (Exception e) {
                return false;
            }
        }

        // ------------------------------------------------------------------ //
        // 会话
        // ------------------------------------------------------------------ //
        private void session(BluetoothSocket socket, BluetoothAdapter adapter) throws Exception {
            activeSocket = socket;
            InputStream in = socket.getInputStream();
            OutputStream o = socket.getOutputStream();
            out = o;

            String self = adapter.getName();
            // hello 与 ops 同用 writeLock：onStartCommand 可能在主线程并发触发回放
            synchronized (writeLock) {
                Proto.write(o, Proto.hello(self == null ? "watch" : self));
                JSONArray pending = MsgStore.pending(SyncService.this);
                if (pending.length() > 0) {
                    Proto.write(o, Proto.opsMsg(pending));
                }
            }
            sConnected = true;
            setState(true, null);

            while (running) {
                JSONObject msg = Proto.read(in);
                if ("list".equals(msg.optString("t"))) {
                    JSONArray items = msg.optJSONArray("items");
                    boolean hasNew = hasNewIds(items);
                    MsgStore.replaceAll(SyncService.this, items);
                    MsgStore.clearPending(SyncService.this); // 已落账
                    Intent i = new Intent(ACTION_LIST).setPackage(getPackageName());
                    i.putExtra(EXTRA_HAS_NEW, hasNew);
                    sendBroadcast(i);
                    if (hasNew) {
                        vibrate();
                    }
                }
            }
        }

        void flushPendingOps() {
            OutputStream o = out;
            if (o == null) {
                return;
            }
            try {
                JSONArray pending = MsgStore.pending(SyncService.this);
                if (pending.length() > 0) {
                    synchronized (writeLock) {
                        Proto.write(o, Proto.opsMsg(pending));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private boolean hasNewIds(JSONArray items) {
            Set<String> old = new HashSet<>();
            for (JSONObject o : MsgStore.items(SyncService.this)) {
                old.add(o.optString("id"));
            }
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.optJSONObject(i);
                    if (o != null && !old.contains(o.optString("id"))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ //
    // 杂项
    // ------------------------------------------------------------------ //
    private void setState(boolean connected, String message) {
        sConnected = connected;
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_CONNECTED, connected);
        if (message != null) {
            i.putExtra(EXTRA_MESSAGE, message);
        }
        sendBroadcast(i);
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(120);
            }
        } catch (Exception ignored) {
        }
    }

    /** 重试等待；被 stop() 打断时返回 false。命名避开 Thread.sleep。 */
    private boolean rest() {
        try {
            Thread.sleep(RETRY_MS);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
