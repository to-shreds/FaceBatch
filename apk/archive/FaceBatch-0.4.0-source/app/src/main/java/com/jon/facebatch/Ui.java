package com.jon.facebatch;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static final int BG = Color.rgb(247, 245, 241);
    public static final int SURFACE = Color.WHITE;
    public static final int INK = Color.rgb(24, 27, 30);
    public static final int MUTED = Color.rgb(103, 108, 114);
    public static final int ACCENT = Color.rgb(45, 97, 87);
    public static final int ACCENT_DARK = Color.rgb(35, 78, 70);
    public static final int ACCENT_SOFT = Color.rgb(222, 235, 231);
    public static final int BORDER = Color.rgb(226, 222, 215);
    public static final int DANGER = Color.rgb(157, 55, 52);
    public static final int SUCCESS = Color.rgb(43, 119, 73);
    public static final int WARNING = Color.rgb(151, 101, 34);

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void prepareWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            window.getDecorView().setSystemUiVisibility(
                    window.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    public static GradientDrawable rounded(int color, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable outlined(int color, int strokeColor, float radiusDp, Context context) {
        GradientDrawable drawable = rounded(color, radiusDp, context);
        drawable.setStroke(dp(context, 1), strokeColor);
        return drawable;
    }

    public static View card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setBackground(outlined(SURFACE, BORDER, 20, context));
        card.setElevation(dp(context, 1));
        return card;
    }

    public static TextView text(Context context, String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0, 1.08f);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        } else {
            view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }
        return view;
    }

    public static Button primaryButton(Context context, String label) {
        return button(context, label, ACCENT, Color.WHITE, ACCENT_DARK, 16);
    }

    public static Button secondaryButton(Context context, String label) {
        return button(context, label, ACCENT_SOFT, ACCENT_DARK, Color.rgb(205, 226, 220), 14);
    }

    public static Button neutralButton(Context context, String label) {
        return button(context, label, SURFACE, INK, Color.rgb(235, 232, 226), 14);
    }

    public static Button dangerButton(Context context, String label) {
        return button(context, label, Color.rgb(250, 235, 234), DANGER, Color.rgb(244, 217, 215), 14);
    }

    private static Button button(Context context, String label, int color, int textColor, int rippleColor, float radius) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        GradientDrawable shape = rounded(color, radius, context);
        if (Build.VERSION.SDK_INT >= 21) {
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null));
        } else {
            button.setBackground(shape);
        }
        button.setStateListAnimator(null);
        return button;
    }

    public static LinearLayout row(Context context, int gravity) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(gravity);
        return row;
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static void marginTop(View view, Context context, int dp) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) raw).topMargin = Ui.dp(context, dp);
            view.setLayoutParams(raw);
        }
    }

    public static View spacer(Context context, int heightDp) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return spacer;
    }
}
