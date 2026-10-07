package com.nukacast.app.diagnostics;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports what the screen actually shows, view by view.
 *
 * <p>Written because "有些东西看不到" and "排版被遮挡" cannot be diagnosed from a screenshot on a TV
 * that is not in front of the developer, and because guessing at paddings made things worse once
 * already. Every visible view is listed with its bounds, whether it is clipped by an ancestor, and
 * how far the scrollable container it lives in can still scroll.
 */
public final class LayoutInspector {
    /** Views deeper than this are summarised rather than listed. */
    private static final int MAX_DEPTH = 14;

    private LayoutInspector() {}

    public static Map<String, Object> report(View root, int screenWidth, int screenHeight) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        List<String> problems = new ArrayList<String>();
        result.put("screen", screenWidth + "x" + screenHeight);
        if (root == null) {
            result.put("error", "没有可检查的界面");
            return result;
        }
        Rect screen = new Rect(0, 0, screenWidth, screenHeight);
        walk(root, screen, 0, false, views, problems);
        result.put("views", views);
        result.put("problems", problems);
        // The focused view explains which item the remote is on, which a screenshot cannot.
        View focused = root.findFocus();
        result.put("focus", focused == null ? "" : describe(focused, screen));
        return result;
    }

    private static void walk(View view, Rect visible, int depth, boolean inScroller,
                             List<Map<String, Object>> out, List<String> problems) {
        if (depth > MAX_DEPTH) return;
        if (view.getVisibility() != View.VISIBLE) return;
        Rect bounds = new Rect();
        if (!view.getGlobalVisibleRect(bounds)) return;
        Rect own = new Rect();
        view.getDrawingRect(own);
        int[] location = new int[2];
        view.getLocationOnScreen(location);

        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("view", describe(view, visible));
        entry.put("onScreen", location[0] + "," + location[1]);
        entry.put("size", view.getWidth() + "x" + view.getHeight());
        entry.put("depth", depth);
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                String shown = text.toString();
                entry.put("text", shown.length() > 40 ? shown.substring(0, 40) : shown);
            }
        }
        if (view instanceof ScrollView) {
            ScrollView scroll = (ScrollView) view;
            entry.put("scroll", scroll.getScrollY() + "/" + Math.max(0,
                    scroll.getChildCount() > 0
                            ? scroll.getChildAt(0).getHeight() - scroll.getHeight() : 0));
        }
        out.add(entry);

        // Text that is cut off is the usual reason a card "cannot be read".
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() != null && text.getLineCount() > 0) {
                int lines = text.getLayout().getLineCount();
                int visibleLines = text.getHeight() / Math.max(1, text.getLineHeight());
                if (lines > visibleLines + 1) {
                    problems.add("文字被截断：" + describe(view, visible)
                            + "（" + visibleLines + "/" + lines + " 行可见）");
                }
            }
        }
        if (!visible.contains(bounds) && view.getWidth() > 0) {
            int overflow = Math.max(bounds.right - visible.right, bounds.bottom - visible.bottom);
            if (overflow > 2) {
                problems.add("超出可视区域 " + overflow + "px：" + describe(view, visible));
            }
        }
        // A view whose own height is larger than what actually shows is being cut by an ancestor — the
        // "有些东西被遮挡看不到" case, which the screen-overflow test above cannot see because the
        // clipped rectangle does fit on screen. Inside a scroll container, clipping is by design (that
        // is how content below the fold works), so those are not reported as problems.
        boolean scroller = view instanceof ScrollView
                || view instanceof android.widget.HorizontalScrollView;
        if (!inScroller && !scroller && view.getWidth() > 0 && view.getHeight() > 0) {
            int lostHeight = view.getHeight() - bounds.height();
            int lostWidth = view.getWidth() - bounds.width();
            if (lostHeight > 4 || lostWidth > 4) {
                problems.add("被上层容器裁掉 " + lostWidth + "x" + lostHeight + "px："
                        + describe(view, visible) + "（实际 " + view.getWidth() + "x" + view.getHeight()
                        + "，可见 " + bounds.width() + "x" + bounds.height() + "）");
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            Rect childVisible = new Rect(visible);
            boolean childInScroller = inScroller || scroller;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), childVisible, depth + 1, childInScroller, out, problems);
            }
        }
    }

    private static String describe(View view, Rect visible) {
        String id = "";
        try {
            if (view.getId() != View.NO_ID && view.getResources() != null) {
                id = String.valueOf(view.getResources().getResourceEntryName(view.getId()));
            }
        } catch (Exception ignored) {
            id = "";
        }
        Rect bounds = new Rect();
        view.getGlobalVisibleRect(bounds);
        return view.getClass().getSimpleName()
                + (id.isEmpty() ? "" : "#" + id)
                + "[" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom + "]";
    }
}
