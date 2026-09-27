package com.astrbot.watchsync.phone;

import android.content.Context;
import android.content.SharedPreferences;

import com.astrbot.watchsync.common.Proto;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 权威消息存储（SharedPreferences 里的一个 JSON 数组）。
 * 排序规则：置顶优先，其余按时间倒序；非置顶最多保留 {@link #MAX_UNPINNED} 条。
 * 所有方法 synchronized：会被主线程（UI/服务启动）与蓝牙线程（ops 回放）并发访问。
 */
public final class MsgStore {

    private static final String PREFS = "watchsync_store";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_UNPINNED = 100;
    private static final int MAX_TEXT_LEN = 8000;

    private MsgStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 返回排序后的消息列表快照。 */
    public static synchronized List<JSONObject> list(Context c) {
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

    public static synchronized JSONArray listJson(Context c) {
        return new JSONArray(list(c));
    }

    public static synchronized int count(Context c) {
        return list(c).size();
    }

    /** 新增一条文本，返回创建的消息项。 */
    public static synchronized JSONObject add(Context c, String text) {
        if (text.length() > MAX_TEXT_LEN) {
            text = text.substring(0, MAX_TEXT_LEN);
        }
        JSONObject item;
        try {
            item = Proto.item(UUID.randomUUID().toString(), text, false, System.currentTimeMillis());
        } catch (JSONException e) {
            return null;
        }
        List<JSONObject> items = list(c);
        items.add(item);
        save(c, trim(items));
        return item;
    }

    /** 回放手表侧操作：{"op":"del","ids":[..]} / {"op":"pin","ids":[..],"on":bool}。 */
    public static synchronized void applyOps(Context c, JSONArray ops) {
        if (ops == null) {
            return;
        }
        List<JSONObject> items = list(c);
        boolean changed = false;
        for (int i = 0; i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op == null) {
                continue;
            }
            List<String> ids = idsFrom(op.optJSONArray("ids"));
            if (ids.isEmpty()) {
                continue;
            }
            String kind = op.optString("op");
            if ("del".equals(kind)) {
                Iterator<JSONObject> it = items.iterator();
                while (it.hasNext()) {
                    if (ids.contains(idOf(it.next()))) {
                        it.remove();
                        changed = true;
                    }
                }
            } else if ("pin".equals(kind)) {
                boolean on = op.optBoolean("on", true);
                for (JSONObject item : items) {
                    if (ids.contains(idOf(item))) {
                        try {
                            item.put("pinned", on);
                            changed = true;
                        } catch (JSONException ignored) {
                        }
                    }
                }
            }
        }
        if (changed) {
            save(c, trim(items));
        }
    }

    /** 手机端 UI 直操单条（置顶/取消置顶/删除）。 */
    public static synchronized void setPinned(Context c, String id, boolean pinned) {
        List<JSONObject> items = list(c);
        for (JSONObject item : items) {
            if (id.equals(idOf(item))) {
                try {
                    item.put("pinned", pinned);
                } catch (JSONException ignored) {
                }
                break;
            }
        }
        save(c, trim(items));
    }

    public static synchronized void remove(Context c, String id) {
        List<JSONObject> items = list(c);
        Iterator<JSONObject> it = items.iterator();
        while (it.hasNext()) {
            if (id.equals(idOf(it.next()))) {
                it.remove();
            }
        }
        save(c, items);
    }

    public static synchronized void clear(Context c) {
        prefs(c).edit().remove(KEY_ITEMS).apply();
    }

    public static String idOf(JSONObject item) {
        return item == null ? "" : item.optString("id");
    }

    /** 置顶全保留；非置顶超出上限时淘汰最旧的。入参需已排序。 */
    private static List<JSONObject> trim(List<JSONObject> sorted) {
        int unpinned = 0;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            if (sorted.get(i).optBoolean("pinned")) {
                continue;
            }
            unpinned++;
            if (unpinned > MAX_UNPINNED) {
                sorted.remove(i);
            }
        }
        return sorted;
    }

    private static void save(Context c, List<JSONObject> items) {
        sort(items);
        prefs(c).edit().putString(KEY_ITEMS, new JSONArray(items).toString()).apply();
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

    private static List<String> idsFrom(JSONArray arr) {
        List<String> ids = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i);
                if (id.length() > 0) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
