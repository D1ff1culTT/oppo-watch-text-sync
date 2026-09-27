package com.astrbot.watchsync.common;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 手机 <-> 手表 蓝牙同步协议。
 *
 * 传输层：经典蓝牙 RFCOMM（SPP），固定 SERVICE_UUID。
 * 帧格式：4 字节大端长度 + UTF-8 JSON 正文。
 *
 * 消息只有三种（t 字段区分）：
 *   hello  手表 -> 手机  连接建立后的问候 {"t":"hello","name":"..."}
 *   ops    手表 -> 手机  手表侧产生的操作回放 {"t":"ops","ops":[{"op":"del","ids":[..]},
 *                      {"op":"pin","ids":[..],"on":true}]}
 *   list   手机 -> 手表  全量权威列表 {"t":"list","items":[{"id","text","pinned","ts"}]}
 *
 * 同步模型：手机是权威端。手表连上后发 hello（如有离线期间积压的 ops 则紧随其后），
 * 手机每收到一条消息或本端新增文本时，都回推一次全量 list。手表收到 list 后才算
 * pending ops 已落账（清空队列），连接断开也不会丢操作。
 */
public final class Proto {

    /** SPP 服务标识，两端一致即可；手机端监听，手表端连接。 */
    public static final String SERVICE_UUID = "7a1f3c2e-5b4d-4a6e-9f80-2c3d4e5f6a7b";

    /** 单帧上限，防御异常流。 */
    private static final int MAX_FRAME_BYTES = 1_000_000;

    private Proto() {
    }

    public static JSONObject msg(String type) throws JSONException {
        return new JSONObject().put("t", type);
    }

    public static JSONObject hello(String deviceName) throws JSONException {
        return msg("hello").put("name", deviceName == null ? "" : deviceName);
    }

    public static JSONObject listMsg(JSONArray items) throws JSONException {
        return msg("list").put("items", items);
    }

    public static JSONObject opsMsg(JSONArray ops) throws JSONException {
        return msg("ops").put("ops", ops);
    }

    /** 构造一条消息项。 */
    public static JSONObject item(String id, String text, boolean pinned, long ts) throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("text", text)
                .put("pinned", pinned)
                .put("ts", ts);
    }

    /** 写一帧并 flush。线程安全需由调用方的写锁保证。 */
    public static void write(OutputStream out, JSONObject message) throws IOException {
        byte[] data = message.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_FRAME_BYTES) {
            throw new IOException("frame too large: " + data.length);
        }
        DataOutputStream dout = new DataOutputStream(out);
        dout.writeInt(data.length);
        dout.write(data);
        dout.flush();
    }

    /** 阻塞读一帧；流正常结束或帧非法都抛 IOException。 */
    public static JSONObject read(InputStream in) throws IOException {
        DataInputStream din = in instanceof DataInputStream
                ? (DataInputStream) in : new DataInputStream(in);
        int len;
        try {
            len = din.readInt();
        } catch (EOFException e) {
            throw new IOException("stream closed", e);
        }
        if (len <= 0 || len > MAX_FRAME_BYTES) {
            throw new IOException("bad frame length: " + len);
        }
        byte[] data = new byte[len];
        din.readFully(data);
        try {
            return new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("bad json frame", e);
        }
    }
}
