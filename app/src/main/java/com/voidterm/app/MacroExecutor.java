package com.voidterm.app;

import android.os.Handler;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and executes macro commands that may contain key combination tags.
 *
 * Syntax:
 *   - Plain text is sent literally (e.g. "clear" sends "clear\r")
 *   - Tags in {braces} send special keys: {ctrl+b}, {esc}, {up}, {enter}
 *   - Escaped braces {{ and }} produce literal { and }
 *   - {wait:N} inserts a pause of N milliseconds
 *
 * Backward compatible: commands without any { are sent as text + \r.
 */
public class MacroExecutor {

    public interface TerminalWriter {
        void write(String text);
    }

    public static void execute(String command, TerminalWriter writer, Handler handler) {
        if (command.indexOf('{') < 0) {
            writer.write(command);
            if (handler != null) {
                handler.postDelayed(() -> writer.write("\r"), 50);
            } else {
                writer.write("\r");
            }
            return;
        }

        List<MacroStep> steps = parse(command);
        if (handler != null) {
            executeSteps(steps, 0, writer, handler);
        } else {
            for (MacroStep step : steps) {
                if (step.text != null) writer.write(step.text);
            }
        }
    }

    private static void executeSteps(List<MacroStep> steps, int index,
                                     TerminalWriter writer, Handler handler) {
        if (index >= steps.size()) return;

        MacroStep step = steps.get(index);
        if (step.delayMs > 0) {
            handler.postDelayed(() -> executeSteps(steps, index + 1, writer, handler),
                    step.delayMs);
        } else {
            writer.write(step.text);
            executeSteps(steps, index + 1, writer, handler);
        }
    }

    static List<MacroStep> parse(String command) {
        List<MacroStep> steps = new ArrayList<>();
        int len = command.length();
        StringBuilder literal = new StringBuilder();
        int i = 0;

        while (i < len) {
            char c = command.charAt(i);

            if (c == '{') {
                if (i + 1 < len && command.charAt(i + 1) == '{') {
                    literal.append('{');
                    i += 2;
                    continue;
                }

                int close = command.indexOf('}', i + 1);
                if (close == -1) {
                    literal.append(c);
                    i++;
                    continue;
                }

                if (literal.length() > 0) {
                    steps.add(new MacroStep(literal.toString()));
                    literal.setLength(0);
                }

                String tagContent = command.substring(i + 1, close);
                String tag = tagContent.toLowerCase();

                if (tag.startsWith("wait:")) {
                    try {
                        int ms = Integer.parseInt(tag.substring(5).trim());
                        steps.add(MacroStep.delay(Math.max(0, Math.min(ms, 5000))));
                    } catch (NumberFormatException ignored) {
                    }
                } else {
                    String resolved = resolveTag(tag);
                    if (resolved != null) {
                        steps.add(new MacroStep(resolved));
                    } else {
                        literal.append('{').append(tagContent).append('}');
                    }
                }

                i = close + 1;
            } else if (c == '}' && i + 1 < len && command.charAt(i + 1) == '}') {
                literal.append('}');
                i += 2;
            } else {
                literal.append(c);
                i++;
            }
        }

        if (literal.length() > 0) {
            steps.add(new MacroStep(literal.toString()));
        }

        return steps;
    }

    static String resolveTag(String tag) {
        if (tag.contains("+")) {
            return resolveCombo(tag);
        }

        switch (tag) {
            case "esc":   return "\033";
            case "enter": return "\r";
            case "tab":   return "\t";
            case "space": return " ";
            case "bksp":  return "\u007F";
            case "del":   return "\033[3~";
            case "ins":   return "\033[2~";
            case "up":    return "\033[A";
            case "down":  return "\033[B";
            case "left":  return "\033[D";
            case "right": return "\033[C";
            case "home":  return "\033[H";
            case "end":   return "\033[F";
            case "pgup":  return "\033[5~";
            case "pgdn":  return "\033[6~";
            case "f1":    return "\033OP";
            case "f2":    return "\033OQ";
            case "f3":    return "\033OR";
            case "f4":    return "\033OS";
            case "f5":    return "\033[15~";
            case "f6":    return "\033[17~";
            case "f7":    return "\033[18~";
            case "f8":    return "\033[19~";
            case "f9":    return "\033[20~";
            case "f10":   return "\033[21~";
            case "f11":   return "\033[23~";
            case "f12":   return "\033[24~";
            default:      return null;
        }
    }

    private static String resolveCombo(String tag) {
        String[] parts = tag.split("\\+");
        if (parts.length < 2) return null;

        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        String key = parts[parts.length - 1].trim();

        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].trim()) {
                case "ctrl":  ctrl = true; break;
                case "alt":   alt = true; break;
                case "shift": shift = true; break;
            }
        }

        if (ctrl && key.length() == 1 && key.charAt(0) >= 'a' && key.charAt(0) <= 'z') {
            char controlChar = (char) (key.charAt(0) - 'a' + 1);
            if (alt) return "\033" + controlChar;
            return String.valueOf(controlChar);
        }

        if (alt && !ctrl && key.length() == 1) {
            return "\033" + key;
        }

        String resolved = resolveTag(key);
        if (resolved == null) return null;

        if (resolved.startsWith("\033[") && resolved.length() > 2) {
            int modifier = 1;
            if (shift) modifier += 1;
            if (alt)   modifier += 2;
            if (ctrl)  modifier += 4;

            if (modifier > 1) {
                char lastChar = resolved.charAt(resolved.length() - 1);
                if (resolved.length() == 3) {
                    return "\033[1;" + modifier + lastChar;
                } else if (lastChar == '~') {
                    return resolved.substring(0, resolved.length() - 1) + ";" + modifier + "~";
                }
            }
        }

        return resolved;
    }

    static class MacroStep {
        final String text;
        final int delayMs;

        MacroStep(String text) {
            this.text = text;
            this.delayMs = 0;
        }

        private MacroStep(int delayMs) {
            this.text = null;
            this.delayMs = delayMs;
        }

        static MacroStep delay(int ms) {
            return new MacroStep(ms);
        }
    }
}
