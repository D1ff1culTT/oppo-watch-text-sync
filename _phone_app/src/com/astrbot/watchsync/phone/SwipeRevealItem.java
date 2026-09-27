package com.astrbot.watchsync.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 历史记录行视图：与手表端一致的滑动操作逻辑——
 * 卡片向左滑开露出右侧「置顶 / 删除」按钮，点卡片复制内容。
 */
public class SwipeRevealItem extends FrameLayout {

    public interface Callbacks {
        void onCardClick();

        void onPinToggle();

        void onDelete();
    }

    private static SwipeRevealItem sOpen;

    private final TextView metaView;
    private final TextView bodyView;
    private final Button pinBtn;
    private final Button deleteBtn;
    private final View cardView;
    private final int touchSlop;
    private final int btnSizePx;
    private final int gapPx;

    private Callbacks callbacks;
    private int revealWidth;
    private boolean dragging;
    private float downX;
    private float downY;
    private float startTx;
    private VelocityTracker velocity;
    private ValueAnimator animator;

    public SwipeRevealItem(Context c) {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        btnSizePx = dp(c, 56);
        gapPx = dp(c, 6);

        // 根视图内边距：让背层与卡片完全对齐，避免按钮从卡片边缘露出来
        int vgap = dp(c, 4);
        setPadding(gapPx, vgap, gapPx, vgap);

        // 背层：右对齐的两个操作按钮
        LinearLayout back = new LinearLayout(c);
        back.setOrientation(LinearLayout.HORIZONTAL);
        back.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        back.setPadding(0, 0, gapPx, 0);
        addView(back, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        pinBtn = actionButton(c, "置顶", 0xFF2E6DF6);
        pinBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwipeRevealItem.closeOpen();
                if (callbacks != null) {
                    callbacks.onPinToggle();
                }
            }
        });
        back.addView(pinBtn, new LinearLayout.LayoutParams(btnSizePx, LayoutParams.MATCH_PARENT));

        deleteBtn = actionButton(c, "删除", 0xFFE53935);
        deleteBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwipeRevealItem.closeOpen();
                if (callbacks != null) {
                    callbacks.onDelete();
                }
            }
        });
        LinearLayout.LayoutParams delLp =
                new LinearLayout.LayoutParams(btnSizePx, LayoutParams.MATCH_PARENT);
        delLp.leftMargin = gapPx;
        back.addView(deleteBtn, delLp);

        // 前景卡片
        final LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 12);
        card.setPadding(pad, pad, pad, pad);

        bodyView = new TextView(c);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bodyView.setMaxLines(3);
        bodyView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(bodyView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        metaView = new TextView(c);
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(c, 6);
        card.addView(metaView, metaLp);

        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        card.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                return handleTouch(card, ev);
            }
        });
        cardView = card;
    }

    private boolean handleTouch(View card, MotionEvent ev) {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                startTx = card.getTranslationX();
                dragging = false;
                if (velocity == null) {
                    velocity = VelocityTracker.obtain();
                }
                velocity.clear();
                velocity.addMovement(ev);
                return true;

            case MotionEvent.ACTION_MOVE: {
                velocity.addMovement(ev);
                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                if (!dragging) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        ensureRevealWidth();
                        if (sOpen != null && sOpen != this) {
                            sOpen.settle(false);
                        }
                        card.getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (Math.abs(dy) > touchSlop) {
                        releaseVelocity();
                        return false; // 纵向意图交给 ListView
                    } else {
                        return true;
                    }
                }
                setTranslation(card, clamp(startTx + dx, -revealWidth, 0));
                return true;
            }

            case MotionEvent.ACTION_UP: {
                float xv = 0;
                if (velocity != null) {
                    velocity.addMovement(ev);
                    velocity.computeCurrentVelocity(1000);
                    xv = velocity.getXVelocity();
                    releaseVelocity();
                }
                if (dragging) {
                    boolean open = card.getTranslationX() < -revealWidth / 2f
                            || xv < -dp(600);
                    settle(open);
                    dragging = false;
                    card.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                if (sOpen == this) {
                    settle(false); // 点击已展开的卡片：收回而不触发复制
                } else if (callbacks != null) {
                    callbacks.onCardClick();
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                releaseVelocity();
                if (dragging) {
                    settle(false);
                    dragging = false;
                    card.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;

            default:
                return false;
        }
    }

    /** 绑定数据与回调；复用行时先复位滑动状态。 */
    public void bind(String meta, String text, boolean pinned, Callbacks cb) {
        metaView.setText(pinned ? "已置顶 · " + meta : meta);
        bodyView.setText(text);
        pinBtn.setText(pinned ? "取消置顶" : "置顶");
        metaView.setTextColor(Ui.textSub);
        bodyView.setTextColor(Ui.textMain);
        cardView.setBackgroundResource(pinned ? Ui.cardPinnedRes() : Ui.cardBgRes());
        callbacks = cb;
        resetSwipe();
    }

    private static Button actionButton(Context c, String label, int color) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(Color.WHITE);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(c, 12));
        bg.setColor(color);
        b.setBackground(bg);
        return b;
    }

    private void ensureRevealWidth() {
        if (revealWidth == 0) {
            revealWidth = pinBtn.getWidth() + deleteBtn.getWidth() + gapPx * 2;
            if (revealWidth <= 0) {
                revealWidth = btnSizePx * 2 + gapPx * 2; // 布局未完成时的兜底
            }
        }
    }

    private void settle(boolean open) {
        ensureRevealWidth();
        View card = getCard();
        float from = card.getTranslationX();
        float to = open ? -revealWidth : 0;
        if (animator != null) {
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(180);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                setTranslation(getCard(), (Float) a.getAnimatedValue());
            }
        });
        animator.start();
        sOpen = open ? this : (sOpen == this ? null : sOpen);
    }

    /** 复用/回收时调用：瞬间复位，无动画。 */
    private void resetSwipe() {
        if (animator != null) {
            animator.cancel();
        }
        setTranslation(getCard(), 0);
        if (sOpen == this) {
            sOpen = null;
        }
    }

    /** 列表滚动时收回已展开的行。 */
    public static void closeOpen() {
        if (sOpen != null) {
            sOpen.settle(false);
        }
    }

    private View getCard() {
        // card 是第二个 child（0 = 背层）
        return getChildAt(1);
    }

    private void setTranslation(View card, float tx) {
        card.setTranslationX(clamp(tx, -revealWidth, 0));
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private void releaseVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private float dp(int v) {
        return dp(getContext(), v);
    }

    private static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
