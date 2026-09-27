package com.astrbot.watchsync.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：连接状态、手动发送（调试/兜底）、历史记录管理。
 * 列表交互与手表端一致：左滑露出「置顶 / 删除」，点卡片复制。
 * 亮/暗主题可通过头部按钮切换，选择持久化。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 1001;

    private TextView status;
    private EditText input;
    private ListView history;
    private HistoryAdapter adapter;
    private List<JSONObject> items;
    private boolean dark;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    private BroadcastReceiver stateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = Ui.load(this);
        setContentView(R.layout.activity_main);
        applyColors();

        status = findViewById(R.id.status);
        input = findViewById(R.id.input);
        history = findViewById(R.id.history);
        items = MsgStore.list(this);
        adapter = new HistoryAdapter();
        history.setAdapter(adapter);
        history.setEmptyView(findViewById(R.id.empty));
        history.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    SwipeRevealItem.closeOpen();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
            }
        });

        findViewById(R.id.send_btn).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.length() == 0) {
                Toast.makeText(this, "先输入文本", Toast.LENGTH_SHORT).show();
                return;
            }
            MsgStore.add(this, text);
            input.setText("");
            pushAndRefresh();
            Toast.makeText(this, SyncService.sConnected ? "已发送至手表 ✓" : "已保存，待手表同步",
                    Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.clear_btn).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setMessage("清空全部（含置顶）文本？")
                        .setPositiveButton("清空", (d, w) -> {
                            MsgStore.clear(MainActivity.this);
                            pushAndRefresh();
                        })
                        .setNegativeButton("取消", null)
                        .show());

        Button themeBtn = findViewById(R.id.theme_btn);
        themeBtn.setText(dark ? "浅色" : "深色");
        themeBtn.setOnClickListener(v -> {
            Ui.save(MainActivity.this, !Ui.dark);
            recreate(); // 重建以应用新主题
        });

        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                runOnUiThread(MainActivity.this::updateStatus);
            }
        };
        registerReceiver(stateReceiver, new IntentFilter(SyncService.ACTION_STATE));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_NOTIFICATIONS);
        }

        // ColorOS 会激进查杀后台服务，申请忽略电池优化以保证同步服务常驻
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    /** 按当前主题给布局控件上色（布局里的 @color 只是初始值）。 */
    private void applyColors() {
        findViewById(R.id.root).setBackgroundColor(Ui.bg);
        findViewById(R.id.header).setBackgroundColor(Ui.headerBg);
        EditText inputView = findViewById(R.id.input);
        inputView.setBackgroundResource(Ui.inputRes());
        inputView.setTextColor(Ui.textMain);
        inputView.setHintTextColor(Ui.textSub);
        ((TextView) findViewById(R.id.section_title)).setTextColor(Ui.textMain);
        ((Button) findViewById(R.id.clear_btn)).setTextColor(Ui.textSub);
        ((TextView) findViewById(R.id.hint)).setTextColor(Ui.hintText);
        ((ImageView) findViewById(R.id.empty_icon)).setColorFilter(Ui.emptyIcon);
        ((TextView) findViewById(R.id.empty_text)).setTextColor(Ui.textSub);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startForegroundService(new Intent(this, SyncService.class));
        refresh();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(stateReceiver);
        super.onDestroy();
    }

    private void pushAndRefresh() {
        startForegroundService(new Intent(this, SyncService.class)
                .setAction(SyncService.ACTION_PUSH));
        refresh();
    }

    private void refresh() {
        items = MsgStore.list(this);
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void updateStatus() {
        boolean conn = SyncService.sConnected;
        String body = (conn ? "已连接手机" : "等待手表连接 · 打开手表 app 自动同步")
                + " · " + items.size() + " 条";
        SpannableString s = new SpannableString((conn ? "● " : "○ ") + body);
        s.setSpan(new ForegroundColorSpan(conn ? 0xFF9FE8A8 : 0xFFFFD08A), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        status.setText(s);
    }

    private void copyItem(JSONObject item) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("text", item.optString("text")));
        }
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private String metaOf(JSONObject item) {
        return timeFmt.format(new Date(item.optLong("ts")));
    }

    private class HistoryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SwipeRevealItem row = convertView instanceof SwipeRevealItem
                    ? (SwipeRevealItem) convertView
                    : new SwipeRevealItem(MainActivity.this);
            final JSONObject item = items.get(position);
            row.bind(metaOf(item), item.optString("text"), item.optBoolean("pinned"),
                    new SwipeRevealItem.Callbacks() {
                        @Override
                        public void onCardClick() {
                            copyItem(item);
                        }

                        @Override
                        public void onPinToggle() {
                            MsgStore.setPinned(MainActivity.this, MsgStore.idOf(item),
                                    !item.optBoolean("pinned"));
                            pushAndRefresh();
                        }

                        @Override
                        public void onDelete() {
                            MsgStore.remove(MainActivity.this, MsgStore.idOf(item));
                            pushAndRefresh();
                        }
                    });
            return row;
        }
    }
}
