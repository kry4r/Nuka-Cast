package com.nukacast.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Builds the detail screen shown before playback: hero, line selector, episode grid.
 *
 * <p>Replaces a dialog that used 23sp headings, 116dp buttons five to a row and no spacing between
 * sections; on a TV that read as one dense block of huge text. Sizes here are chosen so a full
 * episode list of a 100-episode drama fits in six scrollable rows of chips:
 *
 * <ul>
 *   <li>title 19sp bold, meta chips 11sp, plot 12sp over three lines;
 *   <li>episode chips 76×34dp with 12sp labels, eight per row, 6dp gaps;
 *   <li>section headers 12sp, so they label without shouting.
 * </ul>
 */
public final class DetailScreen {
    /** Chips per row; eight leaves room for three-digit episode numbers. */
    private static final int EPISODES_PER_ROW = 8;
    private static final int EPISODE_WIDTH_DP = 76;
    private static final int EPISODE_HEIGHT_DP = 34;

    public interface EpisodeListener {
        void onEpisode(String lineName, int lineIndex, MediaEntry episode);
    }

    public interface LineListener {
        void onLine(int index);
    }

    /** Minimal episode shape so this class stays independent of the TVBox models. */
    public static final class MediaEntry {
        public final String id;
        public final String name;

        public MediaEntry(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private DetailScreen() {}

    public static LinearLayout build(Context context, String title, String meta, String plot,
                                     String[] lineNames, int selectedLine,
                                     MediaEntry[][] episodes, EpisodeListener episodeListener,
                                     LineListener lineListener, View[] firstFocus) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 24), dp(context, 18), dp(context, 24), dp(context, 18));

        TextView titleView = new TextView(context);
        titleView.setText(title == null ? "" : title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(titleView);

        if (meta != null && !meta.isEmpty()) {
            TextView metaView = new TextView(context);
            metaView.setText(meta);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            metaView.setAlpha(0.7f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 4);
            content.addView(metaView, params);
        }

        if (plot != null && !plot.isEmpty()) {
            TextView plotView = new TextView(context);
            plotView.setText(plot);
            plotView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            plotView.setAlpha(0.82f);
            plotView.setMaxLines(3);
            plotView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 8);
            content.addView(plotView, params);
        }

        if (lineNames != null && lineNames.length > 1) {
            content.addView(sectionLabel(context, "播放线路 · " + lineNames.length + " 条",
                    dp(context, 14)));
            LinearLayout lines = new LinearLayout(context);
            lines.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < lineNames.length; i++) {
                final int index = i;
                Button line = chip(context, lineNames[i], 0, false);
                line.setSelected(i == selectedLine);
                line.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        if (lineListener != null) lineListener.onLine(index);
                    }
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 34));
                params.rightMargin = dp(context, 8);
                lines.addView(line, params);
            }
            content.addView(lines);
        }

        if (episodes != null && episodes.length > 0) {
            MediaEntry[] current = episodes[Math.max(0, Math.min(selectedLine, episodes.length - 1))];
            content.addView(sectionLabel(context, "选集 · " + current.length + " 集",
                    dp(context, 14)));
            GridLayout grid = new GridLayout(context);
            grid.setColumnCount(EPISODES_PER_ROW);
            for (int i = 0; i < current.length; i++) {
                final MediaEntry entry = current[i];
                Button episode = chip(context, entry.name == null ? "" : entry.name,
                        EPISODE_WIDTH_DP, true);
                episode.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        if (episodeListener != null) {
                            episodeListener.onEpisode("", 0, entry);
                        }
                    }
                });
                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width = dp(context, EPISODE_WIDTH_DP);
                params.height = dp(context, EPISODE_HEIGHT_DP);
                params.rightMargin = dp(context, 6);
                params.bottomMargin = dp(context, 6);
                grid.addView(episode, params);
                if (firstFocus != null && firstFocus[0] == null) firstFocus[0] = episode;
            }
            content.addView(grid);
        } else {
            content.addView(sectionLabel(context, "该条目没有可用播放线路", dp(context, 14)));
        }

        return content;
    }

    private static TextView sectionLabel(Context context, String text, int topMargin) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setAlpha(0.66f);
        view.setPadding(0, topMargin, 0, dp(context, 6));
        return view;
    }

    private static Button chip(Context context, String text, int widthDp, boolean small) {
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, small ? 12 : 13);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        button.setBackgroundResource(com.nukacast.app.R.drawable.bg_chip);
        button.setTextColor(context.getResources().getColorStateList(
                com.nukacast.app.R.color.text_primary));
        button.setFocusable(true);
        if (widthDp > 0) button.setWidth(dp(context, widthDp));
        return button;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
