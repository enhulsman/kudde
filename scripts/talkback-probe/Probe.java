package dev.kudde.talkbackprobe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public final class Probe extends Instrumentation {
    private static final String APP = "dev.kudde.android.debug";
    private static final String TALKBACK = "com.google.android.marvin.talkback";
    private static final List<String> STEP_ACTIONS = Arrays.asList(
            "Next terminal", "Previous terminal",
            "Next agent that needs you", "Previous agent that needs you");
    private static final List<String> LOG_LEVELS = Arrays.asList(
            "VERBOSE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "ASSERT", "NONE");
    private final StringBuilder result = new StringBuilder();
    private Bundle args;
    private UiAutomation automation;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments;
        start();
    }

    @Override public void onStart() {
        String phase = args.getString("phase", "");
        int status = Activity.RESULT_CANCELED;
        try {
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            switch (phase) {
                case "open": openPane(args.getString("pane")); break;
                case "focus": focus(title(args.getString("pane"))); break;
                case "choice": focusChoice(args.getString("action")); break;
                case "pane": assertPane(args.getString("map")); break;
                case "log": logLevel(args.getString("level")); break;
                default: throw new IllegalArgumentException("Unknown probe phase");
            }
            result.append("PASS ").append(phase).append('\n');
            status = Activity.RESULT_OK;
        } catch (Exception | AssertionError failure) {
            result.append("FAIL ").append(failure.getMessage()).append('\n');
        }
        Bundle output = new Bundle();
        output.putString("stream", result.toString());
        finish(status, output);
    }

    private AccessibilityNodeInfo search(AccessibilityNodeInfo root, Predicate<AccessibilityNodeInfo> match) {
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        while (!nodes.isEmpty()) {
            AccessibilityNodeInfo node = nodes.removeFirst();
            if (node.isVisibleToUser() && match.test(node)) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) nodes.add(child);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo find(String pkg, Predicate<AccessibilityNodeInfo> match) {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !pkg.contentEquals(String.valueOf(root.getPackageName()))) continue;
            AccessibilityNodeInfo node = search(root, match);
            if (node != null) return node;
        }
        return null;
    }

    private AccessibilityNodeInfo await(String pkg, Predicate<AccessibilityNodeInfo> match, String purpose) {
        for (int i = 0; i < 45; i++) {
            AccessibilityNodeInfo node = find(pkg, match);
            if (node != null) return node;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Could not find " + purpose);
    }

    private boolean hasLabel(AccessibilityNodeInfo node, String text) {
        return text.equals(String.valueOf(node.getText()).trim())
                || text.equals(String.valueOf(node.getContentDescription()).trim());
    }

    private void click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo target = node; target != null; target = target.getParent()) {
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        }
        throw new AssertionError("Could not click the requested control");
    }

    private void focus(AccessibilityNodeInfo node) {
        if (!node.isAccessibilityFocused() && !node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
            throw new AssertionError("Could not focus the requested control");
        }
        SystemClock.sleep(350);
    }

    private AccessibilityNodeInfo title(String pane) {
        return await(APP, node -> STEP_ACTIONS.stream().allMatch(name -> node.getActionList().stream()
                .anyMatch(action -> name.equals(String.valueOf(action.getLabel()))))
                && search(node, child -> hasLabel(child, pane) || String.valueOf(child.getContentDescription()).startsWith(pane + ",")) != null,
                "fake pane title with all four step actions");
    }

    private void openPane(String pane) {
        await(APP, node -> true, "the kudde debug window");
        for (int i = 0; i < 3 && find(APP, node -> hasLabel(node, "Spaces")) == null; i++) {
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            SystemClock.sleep(450);
        }
        click(await(APP, node -> hasLabel(node, "Spaces"), "Spaces selector"));
        SystemClock.sleep(500);
        AccessibilityNodeInfo row = scrollTo(APP, pane, 16_000);
        click(row);
        title(pane);
    }

    private AccessibilityNodeInfo scrollTo(String pkg, String label, long waitMillis) {
        long deadline = SystemClock.uptimeMillis() + waitMillis;
        List<AccessibilityNodeInfo.AccessibilityAction> directions = Arrays.asList(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        do {
            for (AccessibilityNodeInfo.AccessibilityAction direction : directions) {
                for (int i = 0; i < 25 && SystemClock.uptimeMillis() < deadline; i++) {
                    AccessibilityNodeInfo match = find(pkg, node -> hasLabel(node, label));
                    if (match != null) return match;
                    AccessibilityNodeInfo scroll = find(pkg, node -> node.getActionList().contains(direction));
                    if (scroll == null || !scroll.performAction(direction.getId())) break;
                    SystemClock.sleep(230);
                }
            }
            SystemClock.sleep(250);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Could not find " + label);
    }

    private void focusChoice(String action) {
        if (!STEP_ACTIONS.contains(action)) throw new AssertionError("Unknown step action");
        if (find(TALKBACK, node -> hasLabel(node, action)) == null) {
            click(await(TALKBACK, node -> hasLabel(node, "Actions"), "TalkBack Actions menu"));
        }
        AccessibilityNodeInfo choice = await(TALKBACK, node -> hasLabel(node, action), "TalkBack step choice");
        focus(choice);
        Rect bounds = new Rect();
        choice.getBoundsInScreen(bounds);
        result.append("Choice ").append(bounds.centerX()).append(' ').append(bounds.centerY()).append('\n');
    }

    private void assertPane(String map) {
        try {
            await(APP, node -> hasLabel(node, map), "expected tab map");
        } catch (AssertionError failure) {
            AccessibilityNodeInfo current = find(APP, node -> String.valueOf(node.getContentDescription())
                    .startsWith("Tab overview, terminal"));
            throw new AssertionError("Expected " + map + ", found "
                    + (current == null ? "no title-bar map" : current.getContentDescription()));
        }
    }

    private void logLevel(String requested) {
        await(TALKBACK, node -> true, "TalkBack settings window");
        click(scrollTo(TALKBACK, "Advanced settings", 4_000));
        SystemClock.sleep(400);
        click(scrollTo(TALKBACK, "Developer settings", 4_000));
        SystemClock.sleep(400);
        click(scrollTo(TALKBACK, "Log output level", 4_000));
        AccessibilityNodeInfo selected = await(TALKBACK, n -> n.isChecked()
                && LOG_LEVELS.contains(String.valueOf(n.getText())), "current TalkBack log level");
        String original = selected.getText().toString();
        result.append("Log level ").append(original).append('\n');
        if (requested == null || original.equals(requested)) {
            automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            return;
        }
        if (!LOG_LEVELS.contains(requested)) throw new AssertionError("Unknown TalkBack log level");
        click(await(TALKBACK, n -> hasLabel(n, requested), "requested TalkBack log level"));
        if (LOG_LEVELS.indexOf(requested) < LOG_LEVELS.indexOf("ERROR")) {
            click(await(TALKBACK, n -> hasLabel(n, "Yes, enable verbose logging"), "TalkBack logging opt-in"));
        }
    }
}
