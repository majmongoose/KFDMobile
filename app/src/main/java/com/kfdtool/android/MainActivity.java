/*
 * This is a port of the KFDtool software made public on github under the MIT license.
 * https://github.com/omahacommsys/KFDtool/tree/master/sw/control
 *
 * Copyright 2019-2020 KFDtool, LLC
 * Copyright 2021-2023 Natalie Moore
 * Copyright 2023 Ilya Smirnov
 * Copyright 2026 Charles Bricker
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.kfdtool.android;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.hardware.usb.UsbDevice;
import com.kfdtool.android.BuildConfig;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.kfdtool.android.databinding.ActivityMainBinding;
import com.kfdtool.android.model.CmdKeyItem;
import com.kfdtool.android.model.DeviceInfo;
import com.kfdtool.android.model.ModelId;
import com.kfdtool.android.p25.DataLinkIndependentProtocol;
import com.kfdtool.android.p25.ManualRekeyApplication;
import com.kfdtool.android.p25.kmm.KeyInfo;
import com.kfdtool.android.protocol.AdapterProtocol;
import com.kfdtool.android.protocol.KfdFrameProtocol;
import com.kfdtool.android.protocol.UdpProtocol;
import com.kfdtool.android.p25.MrEmulatorProtocol;
import com.kfdtool.android.ui.AboutDialogFragment;
import com.kfdtool.android.ui.SettingsDialogFragment;
import com.kfdtool.android.ui.ConnectFragment;
import com.kfdtool.android.ui.ContainerFragment;
import com.kfdtool.android.ui.KeyloadFragment;
import com.kfdtool.android.ui.RadioKeysFragment;
import com.kfdtool.android.usb.UsbSerialManager;

import android.bluetooth.BluetoothManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.widget.Toast;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Main activity. Holds the USB/DLI connection state and all protocol layers.
 * Fragments delegate operations back here via typed callbacks.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG          = "MainActivity";
    private static final String DEBUG_PREFIX = "  ";
    private static final int    DLI_TIMEOUT  = 5000; // ms

    // ── Connection state ──────────────────────────────────────────────────────

    /** Saved DLI configuration (host, port, variant). Null = DLI not configured. */
    public static class DliConfig {
        public final String  host;
        public final int     port;
        public final boolean motVariant;
        DliConfig(String host, int port, boolean motVariant) {
            this.host = host; this.port = port; this.motVariant = motVariant;
        }
    }

    private UsbSerialManager usbManager;
    private volatile KfdFrameProtocol frameProtocol;
    private volatile AdapterProtocol  adapterProtocol;   // non-null = TWI connected
    private volatile DliConfig        dliConfig;         // non-null = DLI active
    private volatile Boolean          dliVerified;       // null=untested, false=failed, true=ready

    private final ExecutorService      executor           = Executors.newSingleThreadExecutor();
    private       ScheduledExecutorService heartbeatScheduler;
    private       ScheduledFuture<?>       heartbeatFuture;

    // ── Fragment instances ────────────────────────────────────────────────────

    private ActivityMainBinding binding;
    private ConnectFragment   connectFragment;
    private KeyloadFragment   keyloadFragment;
    private ContainerFragment containerFragment;
    private RadioKeysFragment radioKeysFragment;

    // ── MR Emulator state ─────────────────────────────────────────────────────

    private MrEmulatorProtocol mrProtocol;
    private Thread             mrThread;

    private Uri pendingOpenUri;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // On the first launch after an app update, drop any saved fragment/activity state
        // from the previous version to avoid subtle restore bugs.
        android.content.SharedPreferences meta = getSharedPreferences("app_meta", MODE_PRIVATE);
        int storedVer  = meta.getInt("version_code", 0);
        int currentVer = BuildConfig.VERSION_CODE;
        if (storedVer != 0 && storedVer != currentVer) savedInstanceState = null;
        meta.edit().putInt("version_code", currentVer).apply();

        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle("v" + BuildConfig.VERSION_NAME);
        }

        usbManager = new UsbSerialManager(this);
        usbManager.setListener(connectionListener);

        connectFragment   = new ConnectFragment();
        keyloadFragment   = new KeyloadFragment();
        containerFragment = new ContainerFragment();
        radioKeysFragment = new RadioKeysFragment();

        if (savedInstanceState == null) showFragment(connectFragment);

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if      (id == R.id.nav_connect)    showFragment(connectFragment);
            else if (id == R.id.nav_keyload)    showFragment(keyloadFragment);
            else if (id == R.id.nav_container)  showFragment(containerFragment);
            else if (id == R.id.nav_radio_keys) showFragment(radioKeysFragment);
            return true;
        });

        if (getIntent() != null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri uri = intent.getData();
            if (isEkcUri(uri)) {
                pendingOpenUri = uri;
                binding.bottomNav.setSelectedItemId(R.id.nav_container);
                showFragment(containerFragment);
            }
        } else {
            handleUsbIntent(intent);
        }
    }

    private boolean isEkcUri(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            // Try OpenableColumns first (works for most content providers)
            try (Cursor cursor = getContentResolver().query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    name = cursor.getString(0);
                }
            } catch (Exception ignored) {}
            // Fall back to MediaStore DATA column for media provider URIs
            if (name == null) {
                try (Cursor cursor = getContentResolver().query(
                        uri, new String[]{android.provider.MediaStore.MediaColumns.DATA},
                        null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        String path = cursor.getString(0);
                        if (path != null) name = new java.io.File(path).getName();
                    }
                } catch (Exception ignored) {}
            }
        }
        // For file:// or if content query failed, use last path segment
        if (name == null) {
            String seg = uri.getLastPathSegment();
            // Only use if it looks like a filename (has a dot), not a numeric MediaStore ID
            if (seg != null && seg.contains(".")) name = seg;
        }
        // If we still can't determine the name, allow through — ContainerIO will reject invalid files
        if (name == null) return true;
        return name.toLowerCase().endsWith(".ekc");
    }

    /** Called by ContainerFragment on resume to pick up a file opened from outside the app. */
    public Uri consumePendingOpenUri() {
        Uri uri = pendingOpenUri;
        pendingOpenUri = null;
        return uri;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopMrEmulation();
        stopHeartbeat();
        executor.shutdownNow();
        if (frameProtocol != null) frameProtocol.stopReceiving();
        usbManager.destroy();
    }

    // ── USB / DLI connection public API ───────────────────────────────────────

    public void connectToUsb()  { usbManager.requestPermission(); }

    public void disconnectUsb() {
        stopHeartbeat();
        if (frameProtocol != null) {
            frameProtocol.stopReceiving();
            frameProtocol = null;
        }
        adapterProtocol = null;
        usbManager.close();
        connectFragment.refreshState();
        connectFragment.setDeviceName(null);
        connectFragment.appendLog("Disconnected.");
    }

    // ── Adapter heartbeat ─────────────────────────────────────────────────────

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(
                () -> executor.execute(this::heartbeatCheck), 30, 30, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    private void heartbeatCheck() {
        KfdFrameProtocol fp = frameProtocol;
        if (fp == null || adapterProtocol == null) return;

        boolean alive;
        try {
            alive = fp.probe(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (alive) return;

        // Probe failed — notify the user and make one reconnect attempt.
        Log.w(TAG, "Heartbeat probe failed, attempting reconnect");
        fp.stopReceiving();
        frameProtocol   = null;
        adapterProtocol = null;
        runOnUiThread(() -> {
            Toast.makeText(this, "Lost connection to KFD adapter. Reconnecting…",
                    Toast.LENGTH_SHORT).show();
            connectFragment.appendLog("Adapter not responding, reconnecting…");
            connectFragment.refreshState();
        });

        KfdFrameProtocol newFp;
        try {
            newFp = KfdFrameProtocol.autoDetect(usbManager, 750);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (newFp == null) {
            stopHeartbeat();
            runOnUiThread(() -> {
                Toast.makeText(this,
                        "Could not reconnect to KFD adapter. Please unplug and reconnect your device.",
                        Toast.LENGTH_LONG).show();
                connectFragment.appendLog("Reconnect failed.");
            });
            usbManager.close();
            return;
        }

        String typeName = newFp.getAdapterType() == KfdFrameProtocol.AdapterType.KFD_TOOL
                ? "Original KFDTool" : "KFDShield/Other";
        frameProtocol   = newFp;
        adapterProtocol = new AdapterProtocol(newFp);
        try {
            adapterProtocol.initialize();
            runOnUiThread(() -> {
                connectFragment.appendLog("Adapter reconnected.");
                connectFragment.refreshState();
            });
        } catch (Exception e) {
            Log.e(TAG, "Reconnect init failed", e);
            newFp.stopReceiving();
            frameProtocol   = null;
            adapterProtocol = null;
            stopHeartbeat();
            runOnUiThread(() -> {
                Toast.makeText(this,
                        "Could not reconnect to KFD adapter. Please unplug and reconnect your device.",
                        Toast.LENGTH_LONG).show();
                connectFragment.appendLog("Reconnect failed.");
            });
            usbManager.close();
        }
    }

    /** Activate DLI mode with the given host/port/variant. */
    public void setDliConfig(String host, int port, boolean motVariant) {
        this.dliConfig    = new DliConfig(host, port, motVariant);
        this.dliVerified  = null;
        connectFragment.refreshState();
    }

    public void testDliConnection(KeyloadCallback callback) {
        if (dliConfig == null) {
            runOnUiThread(() -> callback.onResult(false, "DLI not configured", null));
            return;
        }
        executor.execute(() -> {
            if (!isBluetoothEnabled()) {
                dliVerified = false;
                runOnUiThread(() -> {
                    connectFragment.refreshState();
                    callback.onResult(false,
                            "Bluetooth is not enabled — turn on Bluetooth and connect your radio.", null);
                });
                return;
            }
            if (!isHostRoutable(dliConfig.host)) {
                dliVerified = false;
                runOnUiThread(() -> {
                    connectFragment.refreshState();
                    callback.onResult(false,
                            "No network route to " + dliConfig.host + " — check that your radio"
                            + " is connected via Bluetooth and the IP matches your radio's programming.", null);
                });
                return;
            }
            final String  host = dliConfig.host;
            final int     port = dliConfig.port;
            final boolean mot  = dliConfig.motVariant;
            runOnUiThread(() -> connectFragment.appendLog(
                    "Connecting to " + host + ":" + port + (mot ? " (Motorola)" : " (Standard)") + "…"));
            UdpProtocol udp = new UdpProtocol(host, port, DLI_TIMEOUT, this);
            DataLinkIndependentProtocol proto = new DataLinkIndependentProtocol(udp, mot);
            try {
                proto.initSession();
                proto.endSession();
                dliVerified = true;
                runOnUiThread(() -> {
                    connectFragment.refreshState();
                    callback.onResult(true, "Connected.", null);
                });
            } catch (Exception e) {
                Log.e(TAG, "DLI test failed", e);
                dliVerified = false;
                runOnUiThread(() -> {
                    connectFragment.refreshState();
                    callback.onResult(false, e.getMessage(), null);
                });
            }
        });
    }

    /** Called on DLI tab switch: silently runs a test only if the configured IP is routable. */
    public void autoTestDli() {
        if (dliConfig == null) return;
        executor.execute(() -> {
            if (!isHostRoutable(dliConfig.host)) return;
            final String  host = dliConfig.host;
            final int     port = dliConfig.port;
            final boolean mot  = dliConfig.motVariant;
            runOnUiThread(() -> connectFragment.appendLog(
                    "Connecting to " + host + ":" + port + (mot ? " (Motorola)" : " (Standard)") + "…"));
            UdpProtocol udp = new UdpProtocol(host, port, DLI_TIMEOUT, this);
            DataLinkIndependentProtocol proto = new DataLinkIndependentProtocol(udp, mot);
            try {
                proto.initSession();
                proto.endSession();
                dliVerified = true;
                runOnUiThread(() -> {
                    connectFragment.appendLog("DLI: Connected.");
                    connectFragment.refreshState();
                });
            } catch (Exception e) {
                Log.w(TAG, "Auto DLI test failed", e);
                dliVerified = false;
                runOnUiThread(() -> {
                    connectFragment.appendLog("DLI: " + e.getMessage());
                    connectFragment.refreshState();
                });
            }
        });
    }

    private boolean isBluetoothEnabled() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        android.bluetooth.BluetoothAdapter bt = bm != null ? bm.getAdapter() : null;
        return bt != null && bt.isEnabled();
    }

    private boolean isHostRoutable(String host) {
        try {
            InetAddress target = InetAddress.getByName(host);
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network net : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) continue;
                for (RouteInfo route : lp.getRoutes()) {
                    if (routeContains(route, target)) return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Routability check failed", e);
        }
        return false;
    }

    private static boolean routeContains(RouteInfo route, InetAddress target) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return route.matches(target);
        }
        byte[] tb = target.getAddress();
        byte[] rb = route.getDestination().getAddress().getAddress();
        if (tb.length != rb.length) return false;
        int prefix    = route.getDestination().getPrefixLength();
        int fullBytes = prefix / 8;
        int remainder = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (tb[i] != rb[i]) return false;
        }
        if (remainder > 0 && fullBytes < tb.length) {
            int mask = 0xFF & (0xFF << (8 - remainder));
            if ((tb[fullBytes] & mask) != (rb[fullBytes] & mask)) return false;
        }
        return true;
    }

    /** Deactivate DLI mode (called when user switches back to TWI). */
    public void clearDli() {
        this.dliConfig   = null;
        this.dliVerified = null;
        connectFragment.refreshState();
    }

    public boolean isUsbConnected()  { return usbManager.isOpen(); }
    public boolean isDliActive()     { return dliConfig != null; }
    public boolean isDliVerified()   { return Boolean.TRUE.equals(dliVerified); }
    public boolean isDliFailed()     { return Boolean.FALSE.equals(dliVerified); }
    /** True only after the KFD adapter has been detected and initialized. */
    public boolean isAdapterReady()  { return adapterProtocol != null; }

    /** True if either transport is ready for operations. */
    public boolean isConnected()    { return isAdapterReady() || isDliVerified(); }

    public boolean isMrEmulating()  { return mrThread != null && mrThread.isAlive(); }

    // ── MR Emulator control ───────────────────────────────────────────────────

    public void startMrEmulation(MrEmulatorProtocol.Listener listener) {
        if (adapterProtocol == null || isMrEmulating()) return;
        mrProtocol = new MrEmulatorProtocol(adapterProtocol);
        mrProtocol.setListener(listener);
        mrThread = new Thread(mrProtocol::run, "mr-emulator");
        mrThread.setDaemon(true);
        mrThread.start();
    }

    public void stopMrEmulation() {
        if (mrProtocol != null) mrProtocol.stop();
        if (mrThread   != null) mrThread.interrupt();
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface DeviceInfoCallback {
        void onResult(DeviceInfo info);
    }

    public interface KeyloadCallback {
        void onResult(boolean success, String message, @Nullable String debugLog);
    }

    public interface ViewKeyInfoCallback {
        void onResult(boolean success, String message,
                      @Nullable List<KeyInfo> keys, @Nullable String debugLog);
    }

    // ── Operation helpers ─────────────────────────────────────────────────────

    /** Build the right ManualRekeyApplication for the current connection mode. */
    private ManualRekeyApplication buildRekeyApp() {
        DliConfig cfg = dliConfig;
        if (cfg != null) {
            UdpProtocol udp = new UdpProtocol(cfg.host, cfg.port, DLI_TIMEOUT, this);
            return new ManualRekeyApplication(udp, cfg.motVariant);
        }
        AdapterProtocol ap = adapterProtocol;
        if (ap == null) throw new IllegalStateException("Not connected");
        return new ManualRekeyApplication(ap);
    }

    private void attachProgressListener(ManualRekeyApplication rekey,
                                        StringBuilder userLog,
                                        StringBuilder debugLog,
                                        KeyloadCallback callback) {
        rekey.setProgressListener(msg -> {
            debugLog.append(msg).append("\n");
            if (!msg.startsWith(DEBUG_PREFIX)) {
                userLog.append(msg).append("\n");
                runOnUiThread(() -> callback.onResult(false, userLog.toString(), null));
            }
        });
    }

    private boolean checkConnected(KeyloadCallback callback) {
        if (isMrEmulating()) {
            runOnUiThread(() -> callback.onResult(false, "Stop MR Emulator before performing operations.", null));
            return false;
        }
        if (!isConnected()) {
            runOnUiThread(() -> callback.onResult(false, "Not connected", null));
            return false;
        }
        return true;
    }

    private boolean checkConnectedVki(ViewKeyInfoCallback callback) {
        if (isMrEmulating()) {
            runOnUiThread(() -> callback.onResult(false, "Stop MR Emulator before performing operations.", null, null));
            return false;
        }
        if (!isConnected()) {
            runOnUiThread(() -> callback.onResult(false, "Not connected", null, null));
            return false;
        }
        return true;
    }

    // ── Device info ───────────────────────────────────────────────────────────

    public void readDeviceInfo(DeviceInfoCallback callback) {
        if (adapterProtocol == null) {
            runOnUiThread(() -> callback.onResult(null));
            return;
        }
        executor.execute(() -> {
            DeviceInfo info = new DeviceInfo();
            try {
                int[] apVer = adapterProtocol.readAdapterProtocolVersion();
                info.apVersion = apVer[0] + "." + apVer[1] + "." + apVer[2];
                int[] fwVer = adapterProtocol.readFirmwareVersion();
                info.fwVersion = fwVer[0] + "." + fwVer[1] + "." + fwVer[2];
                int[] hwRev = adapterProtocol.readHardwareRevision();
                info.hwRevision = hwRev[0] + "." + hwRev[1];
                int modelId = adapterProtocol.readModelId();
                info.modelId = ModelId.format(modelId);
                byte[] sn = adapterProtocol.readSerialNumber();
                info.serialNumber = sn.length > 0 ? new String(sn) : "N/A";
                byte[] uid = adapterProtocol.readUniqueId();
                info.uniqueId = uid.length > 0 ? bytesToHex(uid) : "N/A";
                runOnUiThread(() -> callback.onResult(info));
            } catch (Exception e) {
                Log.e(TAG, "readDeviceInfo failed", e);
                runOnUiThread(() -> callback.onResult(null));
            }
        });
    }

    // ── Keyload ───────────────────────────────────────────────────────────────

    public void performKeyload(List<CmdKeyItem> keyItems, KeyloadCallback callback) {
        if (!checkConnected(callback)) return;
        executor.execute(() -> {
            ManualRekeyApplication rekey = buildRekeyApp();
            StringBuilder userLog = new StringBuilder(), debugLog = new StringBuilder();
            attachProgressListener(rekey, userLog, debugLog, callback);
            try {
                rekey.keyload(keyItems);
                userLog.append("Done.");
                runOnUiThread(() -> callback.onResult(true, userLog.toString(), null));
            } catch (Exception e) {
                userLog.append("ERROR: ").append(e.getMessage());
                Log.e(TAG, "Keyload failed", e);
                runOnUiThread(() -> callback.onResult(false, userLog.toString(), debugLog.toString()));
            }
        });
    }

    // ── Erase Keys ────────────────────────────────────────────────────────────

    public void performEraseKeys(List<CmdKeyItem> keyItems, KeyloadCallback callback) {
        if (!checkConnected(callback)) return;
        executor.execute(() -> {
            ManualRekeyApplication rekey = buildRekeyApp();
            StringBuilder userLog = new StringBuilder(), debugLog = new StringBuilder();
            attachProgressListener(rekey, userLog, debugLog, callback);
            try {
                rekey.eraseKeys(keyItems);
                userLog.append("Done.");
                runOnUiThread(() -> callback.onResult(true, userLog.toString(), null));
            } catch (Exception e) {
                userLog.append("ERROR: ").append(e.getMessage());
                Log.e(TAG, "Erase keys failed", e);
                runOnUiThread(() -> callback.onResult(false, userLog.toString(), debugLog.toString()));
            }
        });
    }

    // ── Erase All Keys ────────────────────────────────────────────────────────

    public void performEraseAllKeys(KeyloadCallback callback) {
        if (!checkConnected(callback)) return;
        executor.execute(() -> {
            ManualRekeyApplication rekey = buildRekeyApp();
            StringBuilder userLog = new StringBuilder(), debugLog = new StringBuilder();
            attachProgressListener(rekey, userLog, debugLog, callback);
            try {
                rekey.eraseAllKeys();
                userLog.append("Done.");
                runOnUiThread(() -> callback.onResult(true, userLog.toString(), null));
            } catch (Exception e) {
                userLog.append("ERROR: ").append(e.getMessage());
                Log.e(TAG, "Erase all keys failed", e);
                runOnUiThread(() -> callback.onResult(false, userLog.toString(), debugLog.toString()));
            }
        });
    }

    // ── View Key Info ─────────────────────────────────────────────────────────

    public void performViewKeyInfo(ViewKeyInfoCallback callback) {
        if (!checkConnectedVki(callback)) return;
        executor.execute(() -> {
            ManualRekeyApplication rekey = buildRekeyApp();
            StringBuilder userLog = new StringBuilder(), debugLog = new StringBuilder();
            rekey.setProgressListener(msg -> {
                debugLog.append(msg).append("\n");
                if (!msg.startsWith(DEBUG_PREFIX)) {
                    userLog.append(msg).append("\n");
                    runOnUiThread(() -> callback.onResult(false, userLog.toString(), null, null));
                }
            });
            try {
                List<KeyInfo> keys = rekey.viewKeyInfo();
                userLog.append("Done. ").append(keys.size()).append(" key(s) found.");
                runOnUiThread(() -> callback.onResult(true, userLog.toString(), keys, null));
            } catch (Exception e) {
                userLog.append("ERROR: ").append(e.getMessage());
                Log.e(TAG, "View key info failed", e);
                runOnUiThread(() -> callback.onResult(false, userLog.toString(), null, debugLog.toString()));
            }
        });
    }

    // ── USB connection listener ───────────────────────────────────────────────

    private final UsbSerialManager.ConnectionListener connectionListener =
            new UsbSerialManager.ConnectionListener() {
        @Override public void onConnected(String deviceName) {
            runOnUiThread(() -> {
                connectFragment.appendLog("Connected to: " + deviceName);
                connectFragment.setDeviceName(deviceName);
                connectFragment.refreshState();
            });
            executor.execute(() -> {
                KfdFrameProtocol old = frameProtocol;
                if (old != null) old.stopReceiving();

                runOnUiThread(() -> connectFragment.appendLog("Detecting adapter type…"));
                KfdFrameProtocol fp;
                try {
                    fp = KfdFrameProtocol.autoDetect(usbManager, 750);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (fp == null) {
                    runOnUiThread(() -> connectFragment.appendLog(
                            "Init failed: adapter not responding"));
                    return;
                }

                String typeName = fp.getAdapterType() == KfdFrameProtocol.AdapterType.KFD_TOOL
                        ? "Original KFDTool" : "KFDShield/Other";
                frameProtocol   = fp;
                adapterProtocol = new AdapterProtocol(fp);
                try {
                    adapterProtocol.initialize();
                    String ver = adapterProtocol.getVersionString();
                    startHeartbeat();
                    runOnUiThread(() -> {
                        connectFragment.appendLog("Adapter: " + typeName);
                        connectFragment.appendLog("AP version: " + ver);
                        connectFragment.refreshState();
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Adapter init failed", e);
                    fp.stopReceiving();
                    frameProtocol   = null;
                    adapterProtocol = null;
                    runOnUiThread(() -> {
                        connectFragment.appendLog("Init failed: " + e.getMessage());
                        connectFragment.refreshState();
                    });
                }
            });
        }

        @Override public void onDisconnected() {
            stopMrEmulation();
            stopHeartbeat();
            runOnUiThread(() -> {
                frameProtocol   = null;
                adapterProtocol = null;
                connectFragment.appendLog("Disconnected.");
                connectFragment.refreshState();
            });
        }

        @Override public void onError(String message) {
            runOnUiThread(() -> connectFragment.appendLog("Error: " + message));
        }

        @Override public void onPermissionGranted(UsbDevice device) {
            executor.execute(() -> {
                try {
                    usbManager.open(device);
                } catch (IOException e) {
                    Log.e(TAG, "open failed", e);
                    runOnUiThread(() -> connectFragment.appendLog("Open failed: " + e.getMessage()));
                }
            });
        }

        @Override public void onPermissionDenied() {
            runOnUiThread(() -> connectFragment.appendLog(
                    getString(R.string.error_usb_permission_denied)));
        }
    };

    // ── Toolbar menu ──────────────────────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            new SettingsDialogFragment().show(getSupportFragmentManager(), "settings");
            return true;
        }
        if (item.getItemId() == R.id.action_about) {
            new AboutDialogFragment().show(getSupportFragmentManager(), "about");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void handleUsbIntent(Intent intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) usbManager.requestPermission();
        }
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
