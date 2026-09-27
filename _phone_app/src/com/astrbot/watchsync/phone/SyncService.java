package com.astrbot.watchsync.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

import com.astrbot.watchsync.common.Proto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * 前台同步服务：持有 RFCOMM 服务端 socket，手表端「文本同步」app 打开时会作为客户端连上来。
 *
 * 职责：
 *  1. 监听连接（一次一个手表），会话内读 hello / ops，回推全量 list；
 *  2. 收到 ACTION_PUSH（SendActivity / MainActivity 发送文本后触发）时，若手表在线则立即推送；
 *  3. 通过 ACTION_STATE 广播连接状态给 UI。
 */
public class SyncService extends Service {

    /** 发送方用来触发“若在线则立刻推送”的动作。 */
    public static final String ACTION_PUSH = "com.astrbot.watchsync.phone.PUSH";
    /** 状态广播：listening / connected / count。 */
    public static final String ACTION_STATE = "com.astrbot.watchsync.phone.STATE";
    public static final String EXTRA_LISTENING = "listening";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_COUNT = "count";

    private static final String CHANNEL_ID = "sync";
    private static final int NOTIF_ID = 1;
    private static final long RETRY_MS = 3000;

    /** 供 UI / SendActivity 无需绑定即可读的连接标记。 */
    public static volatile boolean sConnected = false;

    private volatile ServerThread server;
    private volatile Session session;
    private volatile boolean listening;
    private BroadcastReceiver btReceiver;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIF_ID, buildNotification(false));
        // 蓝牙中途被打开时自动恢复监听
        btReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (BluetoothAdapter.STATE_ON
                        == intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    ensureServer();
                }
            }
        };
        registerReceiver(btReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureServer();
        if (intent != null && ACTION_PUSH.equals(intent.getAction())) {
            Session s = session;
            if (s != null) {
                s.sendList();
            }
        }
        broadcastState();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (btReceiver != null) {
            unregisterReceiver(btReceiver);
        }
        ServerThread st = server;
        if (st != null) {
            st.shutdown();
        }
        Session s = session;
        if (s != null) {
            s.close();
        }
        sConnected = false;
        super.onDestroy();
    }

    private synchronized void ensureServer() {
        if (server == null) {
            server = new ServerThread();
            server.start();
        }
    }

    // ------------------------------------------------------------------ //
    // RFCOMM 服务端
    // ------------------------------------------------------------------ //
    private class ServerThread extends Thread {

        private BluetoothServerSocket serverSocket;
        private volatile boolean running = true;

        ServerThread() {
            super("watchsync-server");
        }

        @Override
        public void run() {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            while (running) {
                if (adapter == null || !adapter.isEnabled()) {
                    setListening(false);
                    if (!rest()) {
                        break;
                    }
                    continue;
                }
                try {
                    if (serverSocket == null) {
                        serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                                "WatchSync", UUID.fromString(Proto.SERVICE_UUID));
                    }
                    setListening(true);
                    BluetoothSocket socket = serverSocket.accept();
                    setListening(false);
                    handleSession(socket);
                } catch (Exception e) {
                    setListening(false);
                    closeQuietly(serverSocket);
                    serverSocket = null;
                    if (!running || !rest()) {
                        break;
                    }
                }
            }
        }

        void shutdown() {
            running = false;
            closeQuietly(serverSocket);
            interrupt();
        }

        private boolean rest() {
            try {
                Thread.sleep(RETRY_MS);
                return true;
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    private void handleSession(BluetoothSocket socket) {
        Session s = new Session(socket);
        session = s;
        s.run(); // 串行处理：阻塞直到该连接断开
        if (session == s) {
            session = null;
        }
        broadcastState();
    }

    private void setListening(boolean v) {
        listening = v;
        broadcastState();
    }

    // ------------------------------------------------------------------ //
    // 单个手表连接会话
    // ------------------------------------------------------------------ //
    private class Session {

        final BluetoothSocket socket;
        final Object writeLock = new Object();
        InputStream in;
        OutputStream out;
        volatile boolean open = true;

        Session(BluetoothSocket socket) {
            this.socket = socket;
        }

        void run() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();
                sConnected = true;
                updateNotification(true);
                broadcastState();
                while (open) {
                    JSONObject msg = Proto.read(in);
                    String t = msg.optString("t");
                    if ("hello".equals(t)) {
                        sendList();
                    } else if ("ops".equals(t)) {
                        MsgStore.applyOps(SyncService.this, msg.optJSONArray("ops"));
                        sendList();
                    }
                    // 其它消息忽略，保持前向兼容
                }
            } catch (Exception ignored) {
                // 正常断开或 IO 异常都走到这里
            } finally {
                sConnected = false;
                close();
                updateNotification(false);
                broadcastState();
            }
        }

        void sendList() {
            if (out == null) {
                return;
            }
            try {
                JSONArray items = MsgStore.listJson(SyncService.this);
                synchronized (writeLock) {
                    Proto.write(out, Proto.listMsg(items));
                }
            } catch (Exception e) {
                open = false;
            }
        }

        void close() {
            open = false;
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ //
    // 状态与通知
    // ------------------------------------------------------------------ //
    private Notification buildNotification(boolean connected) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "文本同步", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("手表连接状态");
            nm.createNotificationChannel(channel);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("发送至手表")
                .setContentText(connected ? "手表已连接" : "等待手表连接…")
                .setOngoing(true);
        return b.build();
    }

    private void updateNotification(boolean connected) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(connected));
    }

    private void broadcastState() {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_LISTENING, listening);
        i.putExtra(EXTRA_CONNECTED, session != null);
        i.putExtra(EXTRA_COUNT, MsgStore.count(SyncService.this));
        sendBroadcast(i);
    }

    private static void closeQuietly(BluetoothServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
