package io.freemytv;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.List;

/**
 * Free My Projector: the Bluetooth speaker or headphones playing the sound (like tvOS 26's default speaker). The
 * Control Center's Bluetooth button shows its name and battery and lights up while one is connected; pressed, it opens
 * pairing. Read when a device connects or disconnects, and once a minute (Heat); never while drawing.
 */
final class Bt {
    private Bt() {}

    static final String KEY = "toggle:bt";
    private static volatile String name;     // the connected audio device, or null
    private static volatile int battery = -1;
    private static volatile boolean granting, listening;

    static boolean supported() {
        return BluetoothAdapter.getDefaultAdapter() != null;
    }

    static boolean connected() {
        return name != null;
    }

    /** "JBL Go · 80 %", or `fallback` with nothing connected. */
    static String label(String fallback) {
        String n = name;
        if (n == null) return fallback;
        return battery >= 0 ? n + "  ·  " + battery + " %" : n;
    }

    /** Reads the connected audio device (asynchronous: the A2DP profile answers in a moment). */
    static void refresh(Context c) {
        final Context app = c.getApplicationContext();
        listen(app);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !allowed(app)) {
            name = null;
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                name = null;
                return;
            }
            adapter.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        List<BluetoothDevice> devices = proxy.getConnectedDevices();
                        BluetoothDevice d = devices.isEmpty() ? null : devices.get(0);
                        name = d == null ? null : d.getName() == null ? d.getAddress() : d.getName();
                        battery = d == null ? -1 : batteryOf(d);
                    } catch (RuntimeException e) {
                        AppLog.w("bluetooth: " + e.getMessage());
                    } finally {
                        adapter.closeProfileProxy(profile, proxy);
                    }
                }

                @Override public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.A2DP);
        } catch (RuntimeException e) {   // SecurityException included
            AppLog.w("bluetooth: " + e.getMessage());
        }
    }

    /** Opens pairing: Bluetooth settings if this device has them, else Android TV's accessories, else Settings. */
    static void open(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent[] tries = {
            new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            new Intent().setComponent(new ComponentName("com.android.tv.settings",
                    "com.android.tv.settings.accessories.AddAccessoryActivity")),
            new Intent(android.provider.Settings.ACTION_SETTINGS),
        };
        for (Intent i : tries) {
            if (i.resolveActivity(pm) == null) continue;
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException e) {
                AppLog.w("bluetooth settings: " + e.getMessage());
            }
        }
    }

    /** Android 12+ asks for "nearby devices": granted once through the TV's own ADB, like Now Playing's listener. */
    private static boolean allowed(Context app) {
        if (Build.VERSION.SDK_INT < 31) return true;
        if (app.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED) return true;
        if (!granting) {
            granting = true;
            new Thread(() -> {
                try (Adb adb = Adb.connect(app, 5000, null)) {
                    adb.shell("pm grant " + app.getPackageName() + " android.permission.BLUETOOTH_CONNECT");
                } catch (Exception e) {
                    AppLog.w("bluetooth permission: " + e.getMessage());
                }
            }, "bt-permission").start();
        }
        return false;
    }

    /** The battery the device reports (a hidden method, there since Android 8.1), or -1. */
    private static int batteryOf(BluetoothDevice d) {
        try {
            Object level = BluetoothDevice.class.getMethod("getBatteryLevel").invoke(d);
            int v = level instanceof Integer ? (Integer) level : -1;
            return v >= 0 && v <= 100 ? v : -1;
        } catch (Exception | LinkageError e) {
            return -1;
        }
    }

    /** A speaker connecting or leaving updates the button right away (the process lives for Now Playing). */
    private static void listen(Context app) {
        if (listening) return;
        listening = true;
        IntentFilter f = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                refresh(c);
            }
        }, f);
    }
}
