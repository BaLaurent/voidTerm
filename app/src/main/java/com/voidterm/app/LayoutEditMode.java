package com.voidterm.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.HashMap;
import java.util.Map;

/**
 * Controls the layout edit mode for GameBoyControlPanel.
 * Adds a semi-transparent overlay with grid, a toolbar (Save/Cancel/Reset),
 * and makes all registered buttons draggable.
 */
public class LayoutEditMode {

    public interface Callback {
        void onDone(boolean saved);
    }

    private final FrameLayout panel;
    private final Map<String, View> buttonRegistry;
    private final Callback callback;
    private final Map<String, int[]> initialPositions = new HashMap<>();
    private View overlayView;
    private LinearLayout toolbar;

    public LayoutEditMode(FrameLayout panel, Map<String, View> buttonRegistry, Callback callback) {
        this.panel = panel;
        this.buttonRegistry = buttonRegistry;
        this.callback = callback;

        for (Map.Entry<String, View> entry : buttonRegistry.entrySet()) {
            View v = entry.getValue();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            initialPositions.put(entry.getKey(), new int[]{lp.leftMargin, lp.topMargin});
        }

        addOverlay();
        addToolbar();
        attachDragListeners();
    }

    private void addOverlay() {
        Context ctx = panel.getContext();
        final int gridStep = dp(40);
        overlayView = new View(ctx) {
            private final Paint gridPaint = new Paint() {{
                setColor(0x20FFFFFF);
                setStrokeWidth(1f);
            }};

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                for (int x = gridStep; x < w; x += gridStep) {
                    canvas.drawLine(x, 0, x, h, gridPaint);
                }
                for (int y = gridStep; y < h; y += gridStep) {
                    canvas.drawLine(0, y, w, y, gridPaint);
                }
            }
        };
        overlayView.setBackgroundColor(0x40000000);
        panel.addView(overlayView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void addToolbar() {
        Context ctx = panel.getContext();
        toolbar = new LinearLayout(ctx);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setBackgroundColor(0xCC000000);
        int pad = dp(8);
        toolbar.setPadding(pad, pad, pad, pad);

        Button saveBtn = makeToolbarButton(ctx, "Save", 0xFF4CAF50);
        saveBtn.setOnClickListener(v -> save());

        Button cancelBtn = makeToolbarButton(ctx, "Cancel", 0xFF888888);
        cancelBtn.setOnClickListener(v -> cancel());

        Button resetBtn = makeToolbarButton(ctx, "Reset", 0xFFF44336);
        resetBtn.setOnClickListener(v -> reset());

        toolbar.addView(saveBtn, toolbarBtnParams());
        toolbar.addView(cancelBtn, toolbarBtnParams());
        toolbar.addView(resetBtn, toolbarBtnParams());

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        panel.addView(toolbar, tlp);
    }

    private Button makeToolbarButton(Context ctx, String label, int color) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(4));
        bg.setColor(color);
        btn.setBackground(bg);
        btn.setPadding(dp(16), dp(8), dp(16), dp(8));
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);
        btn.setStateListAnimator(null);
        return btn;
    }

    private LinearLayout.LayoutParams toolbarBtnParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, dp(8), 0);
        return lp;
    }

    private void attachDragListeners() {
        for (View btn : buttonRegistry.values()) {
            btn.setAlpha(0.9f);
            btn.setOnTouchListener(new DragTouchListener());
        }
    }

    private class DragTouchListener implements View.OnTouchListener {
        private float offsetX, offsetY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    offsetX = event.getX();
                    offsetY = event.getY();
                    v.bringToFront();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                    int newLeft = (int) (lp.leftMargin + event.getX() - offsetX);
                    int newTop = (int) (lp.topMargin + event.getY() - offsetY);
                    lp.leftMargin = Math.max(0, Math.min(newLeft, panel.getWidth() - v.getWidth()));
                    lp.topMargin = Math.max(0, Math.min(newTop, panel.getHeight() - v.getHeight()));
                    v.setLayoutParams(lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        }
    }

    private void save() {
        int pw = panel.getWidth();
        int ph = panel.getHeight();
        Map<String, float[]> positions = new HashMap<>();
        for (Map.Entry<String, View> entry : buttonRegistry.entrySet()) {
            View v = entry.getValue();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            float x = pw > 0 ? (float) lp.leftMargin / pw : 0;
            float y = ph > 0 ? (float) lp.topMargin / ph : 0;
            positions.put(entry.getKey(), new float[]{x, y});
        }
        LayoutStore.save(panel.getContext(), positions);
        cleanup();
        callback.onDone(true);
    }

    private void cancel() {
        for (Map.Entry<String, int[]> entry : initialPositions.entrySet()) {
            View v = buttonRegistry.get(entry.getKey());
            if (v != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                lp.leftMargin = entry.getValue()[0];
                lp.topMargin = entry.getValue()[1];
                v.setLayoutParams(lp);
            }
        }
        cleanup();
        callback.onDone(false);
    }

    private void reset() {
        LayoutStore.clear(panel.getContext());
        cleanup();
        callback.onDone(false);
    }

    private void cleanup() {
        panel.removeView(overlayView);
        panel.removeView(toolbar);
        for (View btn : buttonRegistry.values()) {
            btn.setOnTouchListener(null);
            btn.setAlpha(1f);
        }
    }

    private int dp(int value) {
        return PanelUtils.dp(panel.getContext(), value);
    }
}
