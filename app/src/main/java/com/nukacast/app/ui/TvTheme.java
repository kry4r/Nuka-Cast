package com.nukacast.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.nukacast.app.R;

public final class TvTheme {
    private static final String PREFS = "nukacast_ui";
    private static final String LIGHT = "light_theme";

    private TvTheme() {}

    public static boolean isLight(Context context) {
        return preferences(context).getBoolean(LIGHT, false);
    }

    public static boolean toggle(Context context) {
        boolean value = !isLight(context);
        preferences(context).edit().putBoolean(LIGHT, value).apply();
        return value;
    }

    public static int surface(Context context) { return isLight(context) ? Color.rgb(241, 244, 246) : Color.rgb(17, 19, 21); }
    public static int raised(Context context) { return isLight(context) ? Color.WHITE : Color.rgb(25, 28, 31); }
    public static int soft(Context context) { return isLight(context) ? Color.rgb(226, 231, 234) : Color.rgb(34, 38, 42); }
    public static int line(Context context) { return isLight(context) ? Color.rgb(194, 202, 208) : Color.rgb(52, 58, 64); }
    public static int primary(Context context) { return isLight(context) ? Color.rgb(18, 21, 23) : Color.rgb(245, 247, 248); }
    public static int secondary(Context context) { return isLight(context) ? Color.rgb(83, 92, 99) : Color.rgb(168, 176, 183); }
    public static int accent(Context context) { return isLight(context) ? Color.rgb(24, 142, 88) : Color.rgb(99, 214, 154); }
    public static int accentSoft(Context context) { return isLight(context) ? Color.rgb(211, 241, 225) : Color.rgb(20, 61, 43); }

    public static Drawable panel(Context context) {
        return shape(context, raised(context), line(context), 1);
    }

    public static Drawable card(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused},
                shape(context, soft(context), accent(context), 2));
        states.addState(new int[0], shape(context, raised(context), line(context), 1));
        return states;
    }

    public static Drawable focusable(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused},
                shape(context, accentSoft(context), accent(context), 2));
        states.addState(new int[0], shape(context, soft(context), line(context), 1));
        return states;
    }

    public static Drawable navigation(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused},
                shape(context, accentSoft(context), accent(context), 2));
        states.addState(new int[] {android.R.attr.state_selected},
                shape(context, soft(context), line(context), 1));
        states.addState(new int[0], transparent(context));
        return states;
    }

    @SuppressWarnings("deprecation")
    public static void apply(Context context, View root) {
        root.setBackgroundColor(surface(context));
        applyTree(context, root);
    }

    @SuppressWarnings("deprecation")
    private static void applyTree(Context context, View view) {
        Object tag = view.getTag();
        if ("panel".equals(tag)) view.setBackgroundDrawable(panel(context));
        if (view instanceof Button) {
            Button button = (Button) view;
            int id = button.getId();
            boolean primaryNavigation = id == R.id.navHome || id == R.id.navMovies
                    || id == R.id.navCast || id == R.id.navSettings;
            boolean navigation = primaryNavigation || id == R.id.filterAll || id == R.id.filterMovie
                    || id == R.id.filterSeries || id == R.id.filterVariety || id == R.id.filterAnime;
            button.setTextColor(id == R.id.searchButton ? secondary(context) : primary(context));
            button.setBackgroundDrawable(navigation ? navigation(context) : focusable(context));
            if (primaryNavigation) applyNavigationIcon(context, button);
        } else if (view instanceof TextView) {
            TextView text = (TextView) view;
            int color = text.getCurrentTextColor();
            int oldSecondary = context.getResources().getColor(R.color.text_secondary);
            int oldAccent = context.getResources().getColor(R.color.accent);
            int oldPrimary = context.getResources().getColor(R.color.text_primary);
            if (color == oldSecondary) text.setTextColor(secondary(context));
            else if (color == oldAccent) text.setTextColor(accent(context));
            else if (color == oldPrimary) text.setTextColor(primary(context));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyTree(context, group.getChildAt(i));
        }
    }

    private static Drawable transparent(Context context) {
        return shape(context, Color.TRANSPARENT, Color.TRANSPARENT, 0);
    }

    private static GradientDrawable shape(Context context, int fill, int stroke, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, 8));
        if (strokeWidthDp > 0) drawable.setStroke(dp(context, strokeWidthDp), stroke);
        return drawable;
    }

    private static void applyNavigationIcon(Context context, Button button) {
        Drawable source = button.getCompoundDrawables()[1];
        if (source == null) return;
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, tinted(context, source, accent(context)));
        states.addState(new int[] {android.R.attr.state_selected}, tinted(context, source, accent(context)));
        states.addState(new int[0], tinted(context, source, secondary(context)));
        button.setCompoundDrawablesWithIntrinsicBounds(null, states, null, null);
    }

    private static Drawable tinted(Context context, Drawable source, int color) {
        Drawable.ConstantState state = source.getConstantState();
        Drawable copy = state == null ? source.mutate()
                : state.newDrawable(context.getResources()).mutate();
        copy.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        return copy;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
