package com.nukacast.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * The on-screen HUD for full-screen playback and AirPlay mirroring.
 *
 * <p>Design rules, all of them learned from using this on a TV:
 *
 * <ul>
 *   <li><b>Nothing permanent.</b> The previous version parked a "退出投屏" button in the corner that
 *       stayed on screen over the video. The HUD fades out after a few seconds and comes back on any
 *       remote key press.
 *   <li><b>Nothing takes focus.</b> Every child is non-focusable, so the D-pad never lands on the
 *       overlay and the activity keeps receiving keys.
 *   <li><b>Small type.</b> Titles are 18sp, details 12sp — readable from a sofa without covering the
 *       picture.
 * </ul>
 */
public final class PlayerHudView extends FrameLayout {
    /** How long the HUD stays after the last interaction. */
    private static final long AUTO_HIDE_MS = 4000L;
    private static final int TITLE_SP = 18;
    private static final int CHIP_SP = 12;
    private static final int TIME_SP = 12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout topBar;
    private final LinearLayout bottomBar;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView hintView;
    private final TextView positionView;
    private final TextView durationView;
    private final ProgressBar progress;
    private final TextView errorView;
    private final LinearLayout actionRow;
    private final Runnable hide = new Runnable() {
        @Override public void run() {
            animate().alpha(0f).setDuration(220L).withEndAction(new Runnable() {
                @Override public void run() { setVisibility(GONE); }
            }).start();
        }
    };

    public PlayerHudView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setClipChildren(false);

        topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(dp(28), dp(18), dp(28), 0);
        titleView = text(TITLE_SP, true, 0.95f);
        subtitleView = text(CHIP_SP, false, 0.65f);
        LinearLayout.LayoutParams subtitleParams =
                new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(3);
        topBar.addView(titleView);
        topBar.addView(subtitleView, subtitleParams);
        addView(topBar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setVisibility(GONE);
        LayoutParams actionParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        actionParams.bottomMargin = dp(56);
        addView(actionRow, actionParams);

        errorView = text(14, false, 0.95f);
        errorView.setTextColor(Color.parseColor("#FFB4B4"));
        errorView.setVisibility(GONE);
        LayoutParams errorParams = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        errorParams.leftMargin = dp(48);
        errorParams.rightMargin = dp(48);
        addView(errorView, errorParams);

        bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp(28), 0, dp(28), dp(18));
        LinearLayout times = new LinearLayout(context);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);
        positionView = text(TIME_SP, false, 0.7f);
        durationView = text(TIME_SP, false, 0.7f);
        durationView.setGravity(Gravity.END);
        LinearLayout.LayoutParams positionParams =
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams durationParams =
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        times.addView(positionView, positionParams);
        times.addView(durationView, durationParams);
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(3));
        progressParams.topMargin = dp(6);
        bottomBar.addView(times);
        bottomBar.addView(progress, progressParams);
        hintView = text(CHIP_SP, false, 0.6f);
        LinearLayout.LayoutParams hintParams =
                new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(8);
        bottomBar.addView(hintView, hintParams);
        addView(bottomBar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // Nothing in the HUD is focusable: the D-pad must stay with the activity.
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setVisibility(GONE);
    }

    /** One entry of the player menu. */
    public interface ActionListener {
        void onAction(String action);
    }

    /**
     * Shows the player menu.
     *
     * <p>Focus is given to the row (and only then), so the D-pad operates the menu; while it is hidden
     * the HUD never takes focus and thereby never steals keys from the activity.
     */
    public void showActions(String[] labels, String[] actions, String selectedLabel,
                            ActionListener listener) {
        actionRow.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final String action = actions[i];
            boolean selected = labels[i].equals(selectedLabel);
            Button button = new Button(getContext());
            button.setText(selected ? "● " + labels[i] : labels[i]);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinHeight(0);
            button.setPadding(dp(14), 0, dp(14), 0);
            button.setBackgroundResource(com.nukacast.app.R.drawable.bg_chip);
            button.setTextColor(getContext().getResources().getColorStateList(
                    com.nukacast.app.R.color.text_chip));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, dp(34));
            params.setMargins(dp(4), 0, dp(4), 0);
            button.setLayoutParams(params);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { listener.onAction(action); }
            });
            actionRow.addView(button);
        }
        actionRow.setVisibility(VISIBLE);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        if (actionRow.getChildCount() > 0) actionRow.getChildAt(0).requestFocus();
        reveal();
        handler.removeCallbacks(hide);
    }

    public void hideActions() {
        actionRow.setVisibility(GONE);
        actionRow.removeAllViews();
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    public boolean actionsVisible() {
        return actionRow.getVisibility() == VISIBLE;
    }

    /** Shows the HUD for a playback session and hides it again after a few seconds. */
    public void show(String title, String subtitle, String hint, boolean withProgress) {
        titleView.setText(title == null ? "" : title);
        subtitleView.setText(subtitle == null ? "" : subtitle);
        subtitleView.setVisibility(subtitle == null || subtitle.isEmpty() ? GONE : VISIBLE);
        hintView.setText(hint == null ? "" : hint);
        hintView.setVisibility(hint == null || hint.isEmpty() ? GONE : VISIBLE);
        bottomBar.setVisibility(withProgress ? VISIBLE : GONE);
        errorView.setVisibility(GONE);
        reveal();
    }

    /** Brings the HUD back and restarts the auto-hide timer. */
    public void reveal() {
        handler.removeCallbacks(hide);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(120L).start();
        handler.postDelayed(hide, AUTO_HIDE_MS);
    }

    public void hideNow() {
        handler.removeCallbacks(hide);
        setVisibility(GONE);
    }

    public void setProgress(long positionMs, long durationMs) {
        if (durationMs > 0L) {
            progress.setProgress((int) Math.min(1000L, positionMs * 1000L / durationMs));
            positionView.setText(formatTime(positionMs));
            durationView.setText(formatTime(durationMs));
        } else {
            progress.setProgress(0);
            positionView.setText("");
            durationView.setText("");
        }
    }

    public void setSubtitle(String subtitle) {
        subtitleView.setText(subtitle == null ? "" : subtitle);
        subtitleView.setVisibility(subtitle == null || subtitle.isEmpty() ? GONE : VISIBLE);
    }

    /** Error text replaces the title area; the HUD stays visible until the user leaves. */
    public void showError(String message) {
        handler.removeCallbacks(hide);
        errorView.setText(message == null ? "播放失败" : message);
        errorView.setVisibility(VISIBLE);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(120L).start();
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public static String formatTime(long millis) {
        if (millis <= 0L) return "00:00";
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) return String.format(java.util.Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(java.util.Locale.CHINA, "%02d:%02d", minutes, seconds);
    }

    private TextView text(int sp, boolean bold, float alpha) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(Color.WHITE);
        view.setAlpha(alpha);
        view.setShadowLayer(6f, 0f, 1f, Color.parseColor("#CC000000"));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** True when the view hierarchy contains this view, used by tests. */
    public static boolean contains(View root, View child) {
        if (root == child) return true;
        if (!(root instanceof android.view.ViewGroup)) return false;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (contains(group.getChildAt(i), child)) return true;
        }
        return false;
    }
}
