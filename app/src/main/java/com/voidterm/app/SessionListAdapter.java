package com.voidterm.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.terminal.TerminalSession;

import java.util.List;

/**
 * Adapter for the session list in the left drawer.
 * Programmatic layout (no XML) — consistent with the rest of VoidTerm.
 */
public class SessionListAdapter extends BaseAdapter {

    public interface SessionListCallback {
        void onSessionTapped(int index);
        void onSessionCloseRequested(int index);
        void onSessionRenameRequested(int index);
    }

    private final Context context;
    private List<TerminalSession> sessions;
    private int activeIndex;
    private SessionListCallback callback;

    public SessionListAdapter(Context context, SessionListCallback callback) {
        this.context = context;
        this.callback = callback;
        this.sessions = java.util.Collections.emptyList();
        this.activeIndex = -1;
    }

    public void update(List<TerminalSession> sessions, int activeIndex) {
        this.sessions = sessions;
        this.activeIndex = activeIndex;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return sessions.size();
    }

    @Override
    public TerminalSession getItem(int position) {
        return sessions.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout row;
        TextView indicator;
        TextView name;
        TextView closeBtn;

        if (convertView instanceof LinearLayout) {
            row = (LinearLayout) convertView;
            indicator = (TextView) row.getChildAt(0);
            name = (TextView) row.getChildAt(1);
            closeBtn = (TextView) row.getChildAt(2);
        } else {
            row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(12);
            row.setPadding(pad, dp(8), pad, dp(8));

            indicator = new TextView(context);
            indicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            indicator.setTypeface(Typeface.MONOSPACE);
            row.addView(indicator, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            name.setTypeface(Typeface.MONOSPACE);
            name.setPadding(dp(8), 0, dp(8), 0);
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            closeBtn = new TextView(context);
            closeBtn.setText("\u2715"); // ✕
            closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            closeBtn.setTypeface(Typeface.MONOSPACE);
            closeBtn.setTextColor(0xFFAAAAAA);
            closeBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.addView(closeBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        boolean isActive = position == activeIndex;
        TerminalSession session = sessions.get(position);

        indicator.setText("\u25CF"); // ●
        indicator.setTextColor(isActive ? 0xFF44CC44 : 0xFF666666);

        String sessionName = session.mSessionName;
        if (sessionName == null || sessionName.isEmpty()) {
            sessionName = "Session " + (position + 1);
        }
        name.setText(sessionName);
        name.setTextColor(isActive ? Color.WHITE : 0xFFBBBBBB);

        final int idx = position;
        row.setOnClickListener(v -> {
            if (callback != null) callback.onSessionTapped(idx);
        });
        row.setOnLongClickListener(v -> {
            if (callback != null) callback.onSessionRenameRequested(idx);
            return true;
        });
        closeBtn.setOnClickListener(v -> {
            if (callback != null) callback.onSessionCloseRequested(idx);
        });

        return row;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
