package com.astrbot.watchsync.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 「发送至手表」入口：
 *  1. 文本选择菜单：android.intent.action.PROCESS_TEXT（长按选中文字出现）；
 *  2. 系统分享：android.intent.action.SEND（兜底入口）。
 *
 * 交互为“直接发送”：先落库（离线也能存），再触发服务推送，最后按连接状态提示。
 * 手表 app 打开时会连上手机服务（断线 10 秒重试），因此发送后最迟约 10 秒自动送达。
 */
public class SendActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String text = extractText(getIntent());
        if (text == null || text.trim().length() == 0) {
            Toast.makeText(this, "没有可发送的文本", Toast.LENGTH_SHORT).show();
        } else {
            MsgStore.add(this, text.trim());
            boolean connected = SyncService.sConnected;
            startForegroundService(new Intent(this, SyncService.class)
                    .setAction(SyncService.ACTION_PUSH));
            Toast.makeText(this,
                    connected ? "已发送至手表 ✓" : "已保存，手表打开「文本同步」后自动同步",
                    Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private String extractText(Intent intent) {
        if (intent == null) {
            return null;
        }
        CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (cs == null) {
            cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        }
        return cs == null ? null : cs.toString();
    }
}
