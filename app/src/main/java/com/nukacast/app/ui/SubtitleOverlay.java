package com.nukacast.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The subtitle line drawn over the picture.
 *
 * <p>Deliberately not part of {@link PlayerHudView}: the HUD hides itself a few seconds after the last
 * key press, and a subtitle that vanishes with the controls would be useless. This overlay is added to
 * the same frame as the video and only ever shows the current cue.
 */
public final class SubtitleOverlay extends TextView {

    /** Readable from a sofa without covering the picture. */
    private static final int SUBTITLE_SP = 20;
    /** Clear of the bottom bar when the controls are up. */
    private static final int BOTTOM_DP = 56;
    private static final int SIDE_DP = 60;

    public SubtitleOverlay(Context context) {
        super(context);
        setTextColor(Color.WHITE);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_SP);
        setGravity(Gravity.CENTER);
        setMaxLines(3);
        setPadding(0, 0, 0, 0);
        // A shadow rather than a background box: a box cuts the picture into bands, which is not how a
        // broadcast subtitle looks.
        setShadowLayer(dp(2), 0, dp(1), Color.parseColor("#CC000000"));
        setVisibility(GONE);
    }

    /** Layout parameters that keep the line centred near the bottom of the picture. */
    public FrameLayout.LayoutParams layoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        params.bottomMargin = dp(BOTTOM_DP);
        params.leftMargin = dp(SIDE_DP);
        params.rightMargin = dp(SIDE_DP);
        return params;
    }

    /** Shows a subtitle line, or hides the overlay when there is nothing to say. */
    public void setLine(String text) {
        String line = text == null ? "" : text.trim();
        if (line.isEmpty()) {
            setText("");
            setVisibility(GONE);
            return;
        }
        setText(line);
        setVisibility(VISIBLE);
        bringToFront();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
