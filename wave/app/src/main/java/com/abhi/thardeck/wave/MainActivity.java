package com.abhi.thardeck.wave;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.abhi.thardeck.wave.engine.Tuning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Status, Start and Stop, calibration, tuning, relay settings, autostart.
 *
 * Also the only door to the camera service: a camera foreground service can
 * only be started while the app is in the foreground. For adb and the boot
 * receiver it takes extras:
 *
 *   start      (bool) start the service
 *   stop       (bool) stop the service
 *   finish     (bool) close the screen once done
 *
 * Settings extras, honoured only with auth equal to the current relay token:
 *
 *   auth       (string) the current relay token
 *   autostart  (bool) set the autostart-on-boot switch
 *   delegate   (string) gpu or cpu, for the hand landmarker
 *   token      (string) new relay token; takes effect on the next discovery
 *   manual     (string) manual phone address, empty to clear
 *   tiltinvert (bool) flip which way of tilting is volume up
 */
public class MainActivity extends Activity {

    public static final String EXTRA_START = "start";
    public static final String EXTRA_STOP = "stop";
    public static final String EXTRA_AUTOSTART = "autostart";
    public static final String EXTRA_FINISH = "finish";
    public static final String EXTRA_DELEGATE = "delegate";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_MANUAL = "manual";
    public static final String EXTRA_AUTH = "auth";
    public static final String EXTRA_TILT_INVERT = "tiltinvert";

    private static final int REQ_PERMS = 1;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private CalibView calib;
    private Button calibBtn;
    private final List<Runnable> sliderRefreshers = new ArrayList<>();
    private boolean startWhenGranted = false;

    private final Runnable tick = new Runnable() { @Override public void run() {
        refresh();
        if (calib != null && calib.getVisibility() == View.VISIBLE) calib.invalidate();
        h.postDelayed(this, Wave.calibrating ? 100 : 500);
    }};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Wave.tuning(this);
        buildUi();
        handleExtras(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleExtras(i);
    }

    private void handleExtras(Intent i) {
        if (i == null) return;
        boolean transientLaunch = i.getBooleanExtra(EXTRA_FINISH, false);
        // The self-closing launch (boot, adb) must be visible to start a camera
        // service, and in a car the tablet is usually on its lock screen. It
        // shows over the lock screen for the second this takes; nothing is
        // unlocked, and the ordinary launcher entry never does this.
        setShowWhenLocked(transientLaunch);
        setTurnScreenOn(transientLaunch);
        boolean wantsSettings = i.hasExtra(EXTRA_AUTOSTART) || i.hasExtra(EXTRA_TOKEN)
                || i.hasExtra(EXTRA_MANUAL) || i.hasExtra(EXTRA_DELEGATE)
                || i.hasExtra(EXTRA_TILT_INVERT);
        if (wantsSettings) {
            // The activity is exported, so settings by extra need the current
            // relay token, or another app could set a token of its choosing and
            // then pass the simulation hook's token check.
            String auth = i.getStringExtra(EXTRA_AUTH);
            if (auth == null || !auth.trim().equals(Wave.token(this))) {
                Wave.log("extras: bad auth, settings ignored");
                wantsSettings = false;
            }
        }
        if (wantsSettings && i.hasExtra(EXTRA_AUTOSTART)) {
            boolean on = i.getBooleanExtra(EXTRA_AUTOSTART, false);
            Wave.setAutostart(this, on);
            Wave.log("autostart set " + (on ? "on" : "off"));
            buildUi();
        }
        if (wantsSettings && i.hasExtra(EXTRA_TOKEN)) {
            Wave.setToken(this, i.getStringExtra(EXTRA_TOKEN));
            Wave.log("token changed"); // never log the token itself
            buildUi();
        }
        if (wantsSettings && i.hasExtra(EXTRA_MANUAL)) {
            Wave.setManualAddress(this, i.getStringExtra(EXTRA_MANUAL));
            Wave.log("manual address " + (Wave.manualAddress(this).isEmpty() ? "cleared" : "set"));
            buildUi();
        }
        if (wantsSettings && i.hasExtra(EXTRA_DELEGATE)) {
            Wave.setDelegatePref(this, i.getStringExtra(EXTRA_DELEGATE));
            Wave.log("delegate preference set to " + Wave.delegatePref(this));
            restartPipelineIfRunning();
            buildUi();
        }
        if (wantsSettings && i.hasExtra(EXTRA_TILT_INVERT)) {
            boolean on = i.getBooleanExtra(EXTRA_TILT_INVERT, false);
            Wave.tuning(this);
            Wave.saveTiltInvert(this, on);
            Wave.log("tilt direction " + (on ? "inverted" : "normal"));
            buildUi();
        }
        if (i.getBooleanExtra(EXTRA_STOP, false)) stopService();
        if (i.getBooleanExtra(EXTRA_START, false)) startService();
        if (transientLaunch) {
            // Give the service a moment to reach startForeground while we are
            // still the foreground app.
            h.postDelayed(new Runnable() { @Override public void run() { finish(); } }, 1500);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        setCalibrating(false);
    }

    // ---- service ---------------------------------------------------------------

    private void startService() {
        if (Wave.service != null) {
            Wave.log("start requested, service already running, resuming if paused");
            startService(new Intent(this, GestureService.class).setAction(GestureService.ACTION_RESUME));
            return;
        }
        if (!hasPerms()) {
            startWhenGranted = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
            return;
        }
        Wave.log("start requested from the app screen");
        startForegroundService(new Intent(this, GestureService.class));
    }

    private void restartPipelineIfRunning() {
        if (Wave.service != null) {
            startService(new Intent(this, GestureService.class).setAction(GestureService.ACTION_RESTART));
        }
    }

    private void stopService() {
        Wave.log("stop requested from the app screen");
        stopService(new Intent(this, GestureService.class));
    }

    private boolean hasPerms() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_PERMS && startWhenGranted && hasPerms()) {
            startWhenGranted = false;
            startService();
        }
    }

    private void setCalibrating(boolean on) {
        Wave.calibrating = on;
        if (!on) Wave.calibFrame = null;
        if (calib != null) calib.setVisibility(on ? View.VISIBLE : View.GONE);
        if (calibBtn != null) calibBtn.setText(on ? "Close calibrate" : "Calibrate");
    }

    // ---- UI ----------------------------------------------------------------------

    private void buildUi() {
        sliderRefreshers.clear();
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Thar Deck Wave");
        title.setTextSize(24);
        col.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(status);

        LinearLayout row = hrow();
        row.addView(button("Start", new View.OnClickListener() {
            @Override public void onClick(View v) { startService(); }
        }));
        row.addView(button("Stop", new View.OnClickListener() {
            @Override public void onClick(View v) { stopService(); }
        }));
        calibBtn = button("Calibrate", new View.OnClickListener() {
            @Override public void onClick(View v) { setCalibrating(!Wave.calibrating); }
        });
        row.addView(calibBtn);
        if (!Settings.canDrawOverlays(this)) {
            row.addView(button("Allow HUD overlay", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                }
            }));
        }
        col.addView(row);

        calib = new CalibView(this);
        calib.setVisibility(Wave.calibrating ? View.VISIBLE : View.GONE);
        col.addView(calib);

        // ---- autostart ----
        Switch auto = new Switch(this);
        auto.setText("Autostart on boot");
        auto.setChecked(Wave.autostart(this));
        auto.setPadding(0, pad, 0, pad / 2);
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                Wave.setAutostart(MainActivity.this, on);
                Wave.log("autostart set " + (on ? "on" : "off"));
            }
        });
        col.addView(auto);

        // ---- relay ----
        col.addView(section("Phone relay"));
        final EditText token = field("Token (default thardeck)", Wave.token(this), false);
        final EditText manual = field("Manual phone address (optional)", Wave.manualAddress(this), true);
        col.addView(token);
        col.addView(manual);
        LinearLayout relayRow = hrow();
        relayRow.addView(button("Save and find phone", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Wave.setToken(MainActivity.this, token.getText().toString());
                Wave.setManualAddress(MainActivity.this, manual.getText().toString());
                Wave.log("relay settings saved");
                GestureService s = Wave.service;
                if (s != null) s.rediscover("settings changed");
            }
        }));
        col.addView(relayRow);

        // ---- tuning ----
        col.addView(section("Tuning (live, saved on change)"));
        final Tuning tu = Wave.tuning(this);
        final Switch invert = new Switch(this);
        invert.setText("Tilt: invert direction (which way is volume up)");
        invert.setChecked(tu.tiltInvert);
        invert.setPadding(0, pad / 4, 0, pad / 2);
        invert.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                Wave.saveTiltInvert(MainActivity.this, on);
                Wave.log("tilt direction " + (on ? "inverted" : "normal"));
            }
        });
        sliderRefreshers.add(new Runnable() { @Override public void run() {
            invert.setChecked(tu.tiltInvert);
        }});
        col.addView(invert);
        for (final Tuning.Param p : Tuning.PARAMS) {
            final TextView label = new TextView(this);
            final SeekBar bar = new SeekBar(this);
            final int steps = (int) Math.round((p.max - p.min) / p.step);
            bar.setMax(steps);
            final Runnable sync = new Runnable() { @Override public void run() {
                double v = tu.get(p.key);
                label.setText(String.format(Locale.ROOT, "%s: %s", p.label, fmt(v, p.step)));
                bar.setProgress((int) Math.round((v - p.min) / p.step));
            }};
            sync.run();
            sliderRefreshers.add(sync);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int prog, boolean user) {
                    if (!user) return;
                    double v = p.min + prog * p.step;
                    Wave.saveTuning(MainActivity.this, p.key, v);
                    label.setText(String.format(Locale.ROOT, "%s: %s", p.label, fmt(v, p.step)));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {
                    Wave.log("tuning: " + p.key + " = " + fmt(tu.get(p.key), p.step));
                }
            });
            col.addView(label);
            col.addView(bar);
        }
        col.addView(button("Reset tuning to defaults", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Wave.resetTuning(MainActivity.this);
                for (Runnable r : sliderRefreshers) r.run();
                Wave.log("tuning: reset to defaults");
            }
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        refresh();
    }

    private void refresh() {
        if (status == null) return;
        GestureService s = Wave.service;
        String state = s == null ? "stopped" : Wave.serviceState;
        StringBuilder b = new StringBuilder();
        b.append("Service: ").append(state).append('\n');
        b.append("Camera: ").append(Wave.cameraInfo)
                .append(String.format(Locale.ROOT, ", %.1f fps from sensor", Wave.cameraFps)).append('\n');
        b.append(String.format(Locale.ROOT, "Analysed: %.1f fps, inference %.0f ms, delegate %s",
                Wave.analysedFps, Wave.inferMs, Wave.delegate)).append('\n');
        b.append(String.format(Locale.ROOT, "Presence gate: %s, motion %.1f (wakes above %.1f)",
                Wave.gateOn ? "on" : "off", Wave.motion, Wave.TUNING.motionMinDiff)).append('\n');
        b.append(String.format(Locale.ROOT, "Light: %s (luma %d), exposure %.1f ms",
                Wave.night ? "night" : "day", Wave.luma, Wave.exposureMs)).append('\n');
        b.append("Hand present: ").append(Wave.handPresent ? "yes" : "no")
                .append(" (").append(Wave.handPct).append("% of last 5 s)").append('\n');
        b.append("Last command: ").append(Wave.lastCommand).append('\n');
        b.append("Phone relay: ").append(Wave.relayTarget).append('\n');
        b.append("Transform: ").append(Wave.transform).append('\n');
        b.append("HUD overlay: ").append(Settings.canDrawOverlays(this) ? "allowed" : "not allowed");
        status.setText(b.toString());
    }

    // ---- small builders -------------------------------------------------------------

    private static String fmt(double v, double step) {
        if (step >= 1) return String.valueOf(Math.round(v));
        if (step >= 0.1) return String.format(Locale.ROOT, "%.1f", v);
        if (step >= 0.01) return String.format(Locale.ROOT, "%.2f", v);
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTextColor(Color.rgb(79, 195, 247));
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        t.setPadding(0, p, 0, p / 3);
        return t;
    }

    private EditText field(String hint, String value, boolean uri) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(uri ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }
}
