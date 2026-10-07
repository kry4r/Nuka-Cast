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

    public MediaCardView(Context context) {
        super(context);
    }

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
        addView(artwork, new LayoutParams(LayoutParams.MATCH_PARENT, dp(108)));

        ImageView poster = new ImageView(context);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.addView(poster, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Placeholder sits under the poster: while the image loads (or when it fails) the tile shows
        // the title's first character instead of an empty grey rectangle.
        TextView placeholder = text(34, TvTheme.secondary(context));
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setAlpha(0.55f);
        placeholder.setText(safe(item.name).isEmpty() ? "?" : safe(item.name).substring(0, 1));
        artwork.addView(placeholder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Many CMS hosts reject image requests without a matching Referer, which looks exactly like
        // "the home screen has no thumbnails". The poster's own host is the right referer far more
        // often than not, and costs nothing when a host does not care.
        images.load(item.poster, poster, origin(item.poster));

        if (!safe(item.remarks).isEmpty()) {
            TextView badge = text(11, Color.WHITE);
            badge.setText(item.remarks);
            badge.setSingleLine(true);
            badge.setEllipsize(TextUtils.TruncateAt.END);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(4), dp(1), dp(4), dp(1));
            badge.setBackgroundResource(R.drawable.bg_badge);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(62), dp(18),
                    Gravity.END | Gravity.TOP);
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

        TextView title = text(12, TvTheme.primary(context));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(safe(item.name));
        LayoutParams titleParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(18));
        titleParams.topMargin = dp(4);
        addView(title, titleParams);

        TextView meta = text(11, TvTheme.secondary(context));
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setText(meta(item));
        addView(meta, new LayoutParams(LayoutParams.MATCH_PARENT, dp(15)));

        setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                view.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f)
                        .setDuration(150L).start();
                if (focused) {
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

    /** {@code https://host/} of an image URL, used as the Referer for hotlink-protected hosts. */
    private static String origin(String url) {
        if (url == null) return "";
        int scheme = url.indexOf("://");
        if (scheme < 0) return "";
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(0, slash + 1);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
