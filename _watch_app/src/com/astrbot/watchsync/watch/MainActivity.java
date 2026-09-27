package com.astrbot.watchsync.watch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 手表主界面：打开即连手机同步（按需，零后台）。
 *  - 列表：置顶优先，其余按时间倒序；
 *  - 左滑单条露出「置顶 / 删除」按钮；
 *  - 点卡片看大字全文，可复制；
 *  - 顶部状态条显示同步状态。
 */
public class MainActivity extends Activity {

    private TextView status;
    private ListView list;
    private RowAdapter adapter;
    private List<JSONObject> items = new ArrayList<>();
    private String lastMessage;
    private boolean connected;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    private BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
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

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (SyncService.ACTION_STATE.equals(intent.getAction())) {
                    connected = intent.getBooleanExtra(SyncService.EXTRA_CONNECTED, false);
                    lastMessage = intent.getStringExtra(SyncService.EXTRA_MESSAGE);
                    updateStatus();
                } else if (SyncService.ACTION_LIST.equals(intent.getAction())) {
                    reload();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(SyncService.ACTION_STATE);
        filter.addAction(SyncService.ACTION_LIST);
        registerReceiver(receiver, filter);

        startService(new Intent(this, SyncService.class));
        reload();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        stopService(new Intent(this, SyncService.class));
        super.onDestroy();
    }

    // ------------------------------------------------------------------ //
    // 界面
    // ------------------------------------------------------------------ //
    private void buildUi() {
        // 顶部状态胶囊
        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFFC8D6E4);
        status.setBackgroundResource(R.drawable.status_pill);
        status.setPadding(dp(16), dp(8), dp(16), dp(8));
        status.setMaxWidth(dp(330));

        list = new ListView(this);
        list.setDivider(null);
        list.setBackgroundColor(Color.BLACK);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setPadding(dp(6), 0, dp(6), dp(10));

        // 空态视图（必须挂进布局，setEmptyView 才会生效）
        LinearLayout emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_empty_watch);
        emptyBox.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t1 = new TextView(this);
        t1.setText("还没有收到文本");
        t1.setTextSize(14);
        t1.setTextColor(0xFF667788);
        t1.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.topMargin = dp(12);
        emptyBox.addView(t1, lp1);
        TextView t2 = new TextView(this);
        t2.setText("在手机上长按选中文字\n点「发送至手表」");
        t2.setTextSize(12);
        t2.setTextColor(0xFF4A5866);
        t2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(6);
        emptyBox.addView(t2, lp2);

        FrameLayout listArea = new FrameLayout(this);
        listArea.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        emptyLp.gravity = Gravity.CENTER;
        listArea.addView(emptyBox, emptyLp);
        list.setEmptyView(emptyBox);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(6), dp(10), dp(6), 0);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(status, statusLp);
        root.addView(listArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void reload() {
        items = MsgStore.items(this);
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private void updateStatus() {
        String body = (connected ? "已连接手机"
                : (lastMessage == null ? "正在连接手机…" : lastMessage))
                + " · " + items.size() + " 条";
        SpannableString s = new SpannableString((connected ? "● " : "○ ") + body);
        s.setSpan(new ForegroundColorSpan(connected ? 0xFF9FE8A8 : 0xFF66788C),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        status.setText(s);
    }

    private String metaOf(JSONObject item) {
        return timeFmt.format(new Date(item.optLong("ts")));
    }

    private int dp(int v) {
        return Math.round(TypedValueCompat.apply(this, v));
    }

    // ------------------------------------------------------------------ //
    // 行操作
    // ------------------------------------------------------------------ //
    private void togglePin(JSONObject item) {
        boolean on = !item.optBoolean("pinned");
        JSONObject op = op("pin", item.optString("id"));
        if (op != null) {
            try {
                op.put("on", on);
            } catch (Exception ignored) {
            }
            MsgStore.applyLocal(this, op);
            SyncService.sendOp(this, op);
        }
        reload();
    }

    private void deleteItem(JSONObject item) {
        JSONObject op = op("del", item.optString("id"));
        if (op != null) {
            MsgStore.applyLocal(this, op);
            SyncService.sendOp(this, op);
        }
        reload();
    }

    private static JSONObject op(String kind, String id) {
        try {
            JSONObject o = new JSONObject();
            o.put("op", kind);
            o.put("ids", new JSONArray().put(id));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private void openDetail(JSONObject item) {
        final String text = item.optString("text");
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(24);
        tv.setTextColor(0xFFF2F6FA);
        tv.setTextIsSelectable(true);
        int p = dp(14);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("文本内容")
                .setView(sv)
                .setNeutralButton("复制", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("text", text));
                    }
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------------ //
    // 适配器
    // ------------------------------------------------------------------ //
    private class RowAdapter extends BaseAdapter {
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
                            openDetail(item);
                        }

                        @Override
                        public void onPinToggle() {
                            togglePin(item);
                        }

                        @Override
                        public void onDelete() {
                            deleteItem(item);
                        }
                    });
            return row;
        }
    }

    /** 小工具：dp 转像素（独立类避免再 import TypedValue）。 */
    private static final class TypedValueCompat {
        static float apply(Context c, int dp) {
            return android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, dp,
                    c.getResources().getDisplayMetrics());
        }
    }
}
