package com.abhi.thardeck;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Presses the head unit server toggle inside Android Auto's settings, and is
 * the single source of truth for whether the server is up.
 *
 * Scoped to Android Auto's package alone (res/xml/tap_service.xml), so it can
 * read and click nothing else. That is a real privacy boundary and the reason
 * to prefer this over a generic automation app: those grant a service that can
 * see every screen; this one sees exactly one.
 *
 * It never acts on its own. ServerService arms it with a direction and a short
 * window; outside that window it stands down.
 *
 * The menu is a single toggle: "Start head unit server" shows when the server
 * is down, "Stop head unit server" when it is up. So the label is ground truth.
 * The tapper opens the overflow, reads the label, clicks only if the state
 * needs to change, then reopens the overflow to confirm the label flipped
 * before reporting success. A loopback port probe was tried and is unreliable
 * on this phone, which is why the menu is read instead.
 */
public class TapService extends AccessibilityService {

    private static final long POLL_ACTIVE_MS = 130;
    private static final long POLL_IDLE_MS = 600;
    private static final long SETTLE_MS = 500;
    private static final int MAX_CLICKS = 2;

    private final Handler h = new Handler(Looper.getMainLooper());

    // Per-arm working state.
    private boolean overflowOpened = false;
    private int clicks = 0;
    private long lastClick = 0;
    private boolean verifying = false;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (Hu.armActive()) work();
            else reset();
            h.postDelayed(this, Hu.armActive() ? POLL_ACTIVE_MS : POLL_IDLE_MS);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        Hu.tapperReady = true;
        Hu.log("TapService connected");
        h.post(poll);
    }

    @Override public boolean onUnbind(android.content.Intent i) {
        Hu.tapperReady = false;
        h.removeCallbacks(poll);
        return super.onUnbind(i);
    }

    @Override public void onDestroy() {
        Hu.tapperReady = false;
        h.removeCallbacks(poll);
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (Hu.armActive()) work();
    }

    @Override public void onInterrupt() {}

    private void reset() {
        overflowOpened = false;
        clicks = 0;
        lastClick = 0;
        verifying = false;
    }

    private void work() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !Hu.AA_PKG.contentEquals(pkg)) return;   // AA only

        boolean wantStart = Hu.armed == Hu.Intent2.START;
        String wantLabel = wantStart ? Hu.MENU_START : Hu.MENU_STOP;   // click this
        String doneLabel = wantStart ? Hu.MENU_STOP : Hu.MENU_START;   // seen when done

        if (!verifying) {
            // Deciding phase.
            if (present(root, doneLabel)) { finish(wantStart, "already"); return; }
            if (present(root, wantLabel)) {
                if (clickText(root, wantLabel)) {
                    clicks++;
                    lastClick = System.currentTimeMillis();
                    verifying = true;
                    overflowOpened = false;
                    Hu.log("clicked: " + wantLabel + " (" + clicks + ")");
                }
                return;
            }
            openOverflowOnce(root);
        } else {
            // Verifying phase: the click closed the menu, so reopen and re-read.
            if (System.currentTimeMillis() - lastClick < SETTLE_MS) return;
            if (present(root, doneLabel)) { finish(wantStart, "done"); return; }
            if (present(root, wantLabel)) {
                // Did not flip. Click again, up to the cap, else report.
                if (clicks < MAX_CLICKS && clickText(root, wantLabel)) {
                    clicks++;
                    lastClick = System.currentTimeMillis();
                    overflowOpened = false;
                    Hu.log("re-clicked: " + wantLabel + " (" + clicks + ")");
                } else if (clicks >= MAX_CLICKS) {
                    finish(wantStart, "unconfirmed");
                }
                return;
            }
            openOverflowOnce(root);
        }
    }

    private void finish(boolean wantStart, String how) {
        String state = wantStart ? "running" : "stopped";
        // "already" and "done" both mean the server is now in the wanted state.
        if ("unconfirmed".equals(how)) {
            Hu.lastResult = (wantStart ? "start" : "stop") + " tapped, unconfirmed at " + now();
        } else {
            Hu.lastKnownState = state;
            Hu.lastResult = ("already".equals(how) ? "already " : "") + state + " at " + now();
        }
        Hu.log("finish: " + Hu.lastResult);
        Hu.disarm();
        reset();
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private static String now() {
        return android.text.format.DateFormat.format("HH:mm:ss", new java.util.Date()).toString();
    }

    private void openOverflowOnce(AccessibilityNodeInfo root) {
        if (overflowOpened) { overflowOpened = false; return; }   // let it settle, retry
        if (clickByDesc(root, Hu.OVERFLOW_DESC)) {
            overflowOpened = true;
            Hu.log("opened overflow");
        }
    }

    private boolean present(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByText(text);
        return ns != null && !ns.isEmpty();
    }

    private boolean clickText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByText(text);
        if (ns == null) return false;
        for (AccessibilityNodeInfo n : ns) {
            if (n == null) continue;
            CharSequence t = n.getText();
            if (t != null && text.contentEquals(t) && clickable(n)) return true;
        }
        return false;
    }

    private boolean clickByDesc(AccessibilityNodeInfo n, String desc) {
        if (n == null) return false;
        CharSequence cd = n.getContentDescription();
        if (cd != null && desc.contentEquals(cd) && clickable(n)) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            if (clickByDesc(n.getChild(i), desc)) return true;
        }
        return false;
    }

    /** Click the node, or the nearest clickable ancestor, since the label is
     *  often not itself the clickable element in a menu row. */
    private boolean clickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int up = 0; up < 6 && cur != null; up++) {
            if (cur.isClickable()) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            cur = cur.getParent();
        }
        return false;
    }
}
