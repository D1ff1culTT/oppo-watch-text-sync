package com.astrbot.watchsync.watch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 手表端本地数据：
 *  - items：最近一次从手机收到的全量列表缓存（离线打开也有内容可看）；
 *  - pending：离线期间在手表上做的删除/置顶操作，连上手机后回放，收到 list 才清空。
 */
public final class MsgStore {

    private static final String PREFS = "watchsync_cache";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_PENDING = "pending";
    private static final int MAX_PENDING = 200;

    private MsgStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 返回排序后的缓存列表快照（置顶优先，其余按时间倒序）。 */
    public static synchronized List<JSONObject> items(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(o);
                }
            }
        } catch (JSONException ignored) {
        }
        sort(out);
        return out;
    }

    /** 用手机推送的全量列表替换缓存。 */
    public static synchronized void replaceAll(Context c, JSONArray items) {
        List<JSONObject> list = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o != null) {
                    list.add(o);
                }
            }
        }
        sort(list);
        prefs(c).edit().putString(KEY_ITEMS, new JSONArray(list).toString()).apply();
    }

    /** 待回放操作队列。 */
    public static synchronized JSONArray pending(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY_PENDING, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public static synchronized void addPending(Context c, JSONObject op) {
        JSONArray arr = pending(c);
        arr.put(op);
        while (arr.length() > MAX_PENDING) {
            // 队列超限时丢最旧的（正常使用到不了这个量）
            JSONArray next = new JSONArray();
            for (int i = 1; i < arr.length(); i++) {
                next.put(arr.opt(i));
            }
            arr = next;
        }
        prefs(c).edit().putString(KEY_PENDING, arr.toString()).apply();
    }

    /** 收到手机回推的 list 后调用：操作已落账到权威端。 */
    public static synchronized void clearPending(Context c) {
        prefs(c).edit().remove(KEY_PENDING).apply();
    }

    /** 本地乐观更新：删除或置顶（同时也会入 pending 队列，由调用方决定）。 */
    public static synchronized void applyLocal(Context c, JSONObject op) {
        String kind = op.optString("op");
        JSONArray idsArr = op.optJSONArray("ids");
        if (idsArr == null || idsArr.length() == 0) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < idsArr.length(); i++) {
            ids.add(idsArr.optString(i));
        }
        List<JSONObject> items = items(c);
        boolean changed = false;
        if ("del".equals(kind)) {
            Iterator<JSONObject> it = items.iterator();
            while (it.hasNext()) {
                if (ids.contains(it.next().optString("id"))) {
                    it.remove();
                    changed = true;
                }
            }
        } else if ("pin".equals(kind)) {
            boolean on = op.optBoolean("on", true);
            for (JSONObject item : items) {
                if (ids.contains(item.optString("id"))) {
                    try {
                        item.put("pinned", on);
                        changed = true;
                    } catch (JSONException ignored) {
                    }
                }
            }
        }
        if (changed) {
            sort(items);
            prefs(c).edit().putString(KEY_ITEMS, new JSONArray(items).toString()).apply();
        }
    }

    private static void sort(List<JSONObject> items) {
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                boolean pa = a.optBoolean("pinned");
                boolean pb = b.optBoolean("pinned");
                if (pa != pb) {
                    return pa ? -1 : 1;
                }
                long ta = a.optLong("ts");
                long tb = b.optLong("ts");
                return ta > tb ? -1 : (ta < tb ? 1 : 0);
            }
        });
    }
}
