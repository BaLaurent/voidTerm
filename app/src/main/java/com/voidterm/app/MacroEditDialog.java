package com.voidterm.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * Dialog for editing a macro button's label and command.
 * Shows two EditText fields (label + command) in a dark-themed AlertDialog.
 */
public class MacroEditDialog {

    public interface OnSaveListener {
        void onSave(String label, String command);
    }

    public static void show(Context context, String currentLabel, String currentCommand,
                            OnSaveListener onSave) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 24);
        layout.setPadding(pad, pad, pad, dp(context, 8));

        EditText labelInput = new EditText(context);
        labelInput.setHint("Label");
        labelInput.setText(currentLabel);
        labelInput.setTextColor(Color.WHITE);
        labelInput.setHintTextColor(0xFF888888);
        labelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        labelInput.setSelectAllOnFocus(true);
        layout.addView(labelInput);

        EditText cmdInput = new EditText(context);
        cmdInput.setHint("Command");
        cmdInput.setText(currentCommand);
        cmdInput.setTextColor(Color.WHITE);
        cmdInput.setHintTextColor(0xFF888888);
        cmdInput.setInputType(InputType.TYPE_CLASS_TEXT);
        cmdInput.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams cmdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cmdLp.topMargin = dp(context, 12);
        layout.addView(cmdInput, cmdLp);

        android.widget.TextView helpText = new android.widget.TextView(context);
        helpText.setText("Keys: {ctrl+b} {esc} {up} {enter} {tab} {f1}\nEx: {ctrl+b}{up}  {esc}:wq{enter}");
        helpText.setTextColor(0xFF888888);
        helpText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = dp(context, 8);
        layout.addView(helpText, helpLp);

        new AlertDialog.Builder(context)
                .setTitle("Edit Macro")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String label = labelInput.getText().toString().trim();
                    String cmd = cmdInput.getText().toString().trim();
                    if (!label.isEmpty() && !cmd.isEmpty()) {
                        onSave.onSave(label, cmd);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
