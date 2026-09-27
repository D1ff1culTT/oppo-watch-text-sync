package com.astrbot.watchsync.phone;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 亮/暗双主题的运行时调色板。
 * 主题选择存 SharedPreferences；切换通过 recreate() 重新应用。
 * 列表行由代码构建（SwipeRevealItem），颜色从这里取。
 */
public final class Ui {

    private static final String PREFS = "settings";
    private static final String KEY_DARK = "dark";

    public static boolean dark;

    public static int bg;
    public static int headerBg;
    public static int textMain;
    public static int textSub;
    public static int hintText;
    public static int emptyIcon;

    private Ui() {
    }

    public static boolean load(Context c) {
        dark = prefs(c).getBoolean(KEY_DARK, false);
        apply();
        return dark;
    }

    public static void save(Context c, boolean d) {
        prefs(c).edit().putBoolean(KEY_DARK, d).apply();
    }

    /** 按当前选择填充调色板。 */
    public static void apply() {
        if (dark) {
            bg = 0xFF10151C;
            headerBg = 0xFF151C26;
            textMain = 0xFFE8EEF5;
            textSub = 0xFF8A97A6;
            hintText = 0xFF6B7885;
            emptyIcon = 0xFF3A4756;
        } else {
            bg = 0xFFF2F5F9;
            headerBg = 0xFF1E88E5;
            textMain = 0xFF1A232E;
            textSub = 0xFF8A97A6;
            hintText = 0xFF9AA7B5;
            emptyIcon = 0xFFC4CEDA;
        }
    }

    public static int cardBgRes() {
        return dark ? R.drawable.card_bg_dark : R.drawable.card_bg;
    }

    public static int cardPinnedRes() {
        return dark ? R.drawable.card_bg_pinned_dark : R.drawable.card_bg_pinned;
    }

    public static int inputRes() {
        return dark ? R.drawable.bg_input_dark : R.drawable.bg_input;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
