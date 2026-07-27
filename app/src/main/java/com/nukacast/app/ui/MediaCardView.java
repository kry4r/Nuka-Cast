package com.nukacast.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.nukacast.app.R;
import com.nukacast.app.tvbox.model.SearchItem;

public final class MediaCardView extends LinearLayout {
    public interface PreviewListener {
        void onPreview(SearchItem item);
    }

    private PreviewListener previewListener;

    public MediaCardView(Context context, SearchItem item, int positionMs, int durationMs,
                         PosterImageLoader images) {
        super(context);
        setOrientation(VERTICAL);
        setFocusable(true);
        setClickable(true);
        setPadding(dp(4), dp(4), dp(4), dp(5));
        setBackgroundDrawable(TvTheme.card(context));
        setClipChildren(false);

        FrameLayout artwork = new FrameLayout(context);
        artwork.setBackgroundColor(TvTheme.soft(context));
        addView(artwork, new LayoutParams(LayoutParams.MATCH_PARENT, dp(224)));

        ImageView poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.addView(poster, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        images.load(item.poster, poster);

        if (!safe(item.remarks).isEmpty()) {
            TextView badge = text(11, Color.WHITE);
            badge.setText(item.remarks);
            badge.setSingleLine(true);
            badge.setEllipsize(TextUtils.TruncateAt.END);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            badge.setBackgroundResource(R.drawable.bg_badge);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(84), dp(24),
                    Gravity.RIGHT | Gravity.TOP);
            badgeParams.setMargins(0, dp(6), dp(6), 0);
            artwork.addView(badge, badgeParams);
        }

        if (durationMs > 0 && positionMs > 0) {
            ProgressBar progress = new ProgressBar(context, null,
                    android.R.attr.progressBarStyleHorizontal);
            progress.setMax(durationMs);
            progress.setProgress(Math.min(positionMs, durationMs));
            progress.setProgressDrawable(getResources().getDrawable(R.drawable.progress_watch));
            FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM);
            artwork.addView(progress, progressParams);
        }

        TextView title = text(14, TvTheme.primary(context));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(safe(item.name));
        LayoutParams titleParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(24));
        titleParams.topMargin = dp(7);
        addView(title, titleParams);

        TextView meta = text(11, TvTheme.secondary(context));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setText(meta(item));
        addView(meta, new LayoutParams(LayoutParams.MATCH_PARENT, dp(18)));

        setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                view.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f)
                        .setDuration(150L).start();
                if (focused) {
                    view.bringToFront();
                    if (previewListener != null) previewListener.onPreview(item);
                }
            }
        });
    }

    public void setPreviewListener(PreviewListener listener) {
        previewListener = listener;
    }

    private TextView text(int sp, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private String meta(SearchItem item) {
        String source = safe(item.siteName);
        String year = safe(item.year);
        if (source.isEmpty()) return year;
        if (year.isEmpty()) return source;
        return source + " · " + year;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
