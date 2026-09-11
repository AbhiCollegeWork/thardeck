package com.abhi.thardeck;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Status and setup, built in code so the no-Gradle build carries no layout
 * resources. Shows what the app is doing, lets the user pick which bonded
 * device is the car, and exposes the same start/stop the service uses so the
 * whole flow can be exercised at a desk.
 */
public class MainActivity extends Activity {

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView status;
    private Runnable ticker;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ensureService();

        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#0f1417"));
        sv.addView(root);
        setContentView(sv);

        title("Thar Deck");
        sub("Starts Android Auto's head unit server when the car connects, and stops it when the car leaves.");

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#cfe8d8"));
        status.setTextSize(13);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        button(Hu.isEnabled(this) ? "Pause watching" : "Resume watching", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Hu.setEnabled(MainActivity.this, !Hu.isEnabled(MainActivity.this));
                startService(new Intent(MainActivity.this, ServerService.class)
                        .setAction(ServerService.ACTION_REFRESH));
                recreate();
            }
        });

        sub("Which Bluetooth device is the car?");
        addDevicePicker();

        button("Enable the accessibility service", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        sub("Test without driving");
        button("Start server now", new View.OnClickListener() {
            @Override public void onClick(View v) { fire(ServerService.ACTION_START_NOW); }
        });
        button("Stop server now", new View.OnClickListener() {
            @Override public void onClick(View v) { fire(ServerService.ACTION_STOP_NOW); }
        });
    }

    private void fire(String action) {
        startForegroundService(new Intent(this, ServerService.class).setAction(action));
        Toast.makeText(this, "sent", Toast.LENGTH_SHORT).show();
    }

    private void addDevicePicker() {
        List<BluetoothDevice> devs = bonded();
        String cur = Hu.carAddr(this);
        if (devs.isEmpty()) { sub("No bonded Bluetooth devices, or permission not granted."); return; }
        for (final BluetoothDevice d : devs) {
            String name = safeName(d);
            boolean sel = cur != null && cur.equalsIgnoreCase(d.getAddress());
            Button bt = new Button(this);
            bt.setAllCaps(false);
            bt.setText((sel ? "✓  " : "     ") + name);
            bt.setTextColor(sel ? Color.parseColor("#7cf7b4") : Color.parseColor("#e6edf0"));
            bt.setBackgroundColor(Color.parseColor(sel ? "#16312a" : "#161c20"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(4), 0, dp(4));
            bt.setLayoutParams(lp);
            bt.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Hu.setCar(MainActivity.this, d.getAddress(), safeName(d));
                    Hu.log("car set to " + safeName(d));
                    recreate();
                }
            });
            root.addView(bt);
        }
    }

    private List<BluetoothDevice> bonded() {
        List<BluetoothDevice> out = new ArrayList<>();
        try {
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad != null && ad.getBondedDevices() != null) out.addAll(ad.getBondedDevices());
        } catch (Throwable t) { Hu.log("bonded list failed: " + t); }
        return out;
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (Throwable t) { return d.getAddress(); }
    }

    private void ensureService() {
        try { startForegroundService(new Intent(this, ServerService.class)); }
        catch (Throwable t) { Hu.log("could not start service: " + t); }
    }

    @Override protected void onResume() {
        super.onResume();
        ticker = new Runnable() { @Override public void run() {
            refreshStatus();
            h.postDelayed(this, 1500);
        }};
        h.post(ticker);
    }

    @Override protected void onPause() {
        super.onPause();
        if (ticker != null) h.removeCallbacks(ticker);
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean acc = accessibilityOn();
        status.setText(
                "Watching: " + (Hu.isEnabled(this) ? "yes" : "paused") + "\n"
              + "Car device: " + Hu.carName(this) + "\n"
              + "Server (last known): " + Hu.lastKnownState + "\n"
              + "Accessibility service: " + (acc ? "on" : "OFF, tap below") + "\n"
              + "Last action: " + Hu.lastResult);
    }

    private boolean accessibilityOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getPackageName() + "/");
        } catch (Throwable t) { return false; }
    }

    // ---- tiny UI helpers ----------------------------------------------------

    private void title(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Color.parseColor("#7cf7b4"));
        t.setTextSize(24); t.setPadding(0, 0, 0, dp(4));
        root.addView(t);
    }
    private void sub(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(Color.parseColor("#9fb4ad"));
        t.setTextSize(13); t.setPadding(0, dp(14), 0, dp(6));
        root.addView(t);
    }
    private void button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(s);
        b.setTextColor(Color.parseColor("#0f1417"));
        b.setBackgroundColor(Color.parseColor("#5be39b"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        root.addView(b);
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
