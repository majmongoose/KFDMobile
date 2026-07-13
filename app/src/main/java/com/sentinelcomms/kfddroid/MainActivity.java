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
package com.sentinelcomms.kfddroid;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.hardware.usb.UsbDevice;
import com.sentinelcomms.kfddroid.BuildConfig;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.sentinelcomms.kfddroid.databinding.ActivityMainBinding;
import com.sentinelcomms.kfddroid.model.CmdKeyItem;
import com.sentinelcomms.kfddroid.model.DeviceInfo;
import com.sentinelcomms.kfddroid.model.ModelId;
import com.sentinelcomms.kfddroid.p25.DataLinkIndependentProtocol;
import com.sentinelcomms.kfddroid.p25.ManualRekeyApplication;
import com.sentinelcomms.kfddroid.p25.kmm.KeyInfo;
import com.sentinelcomms.kfddroid.protocol.AdapterProtocol;
import com.sentinelcomms.kfddroid.protocol.KfdFrameProtocol;
import com.sentinelcomms.kfddroid.protocol.UdpProtocol;
import com.sentinelcomms.kfddroid.p25.MrEmulatorProtocol;
import com.sentinelcomms.kfddroid.protocol.UdpTransport;
import com.sentinelcomms.kfddroid.ui.AboutDialogFragment;
import com.sentinelcomms.kfddroid.usb.rndis.UsbRndisManager;
import com.sentinelcomms.kfddroid.usb.rndis.UsbRndisUdpTransport;
import com.sentinelcomms.kfddroid.ui.SettingsDialogFragment;
import com.sentinelcomms.kfddroid.ui.ConnectFragment;
import com.sentinelcomms.kfddroid.ui.ContainerFragment;
import com.sentinelcomms.kfddroid.ui.KeyloadFragment;
import com.sentinelcomms.kfddroid.ui.RadioKeysFragment;
import com.sentinelcomms.kfddroid.usb.UsbSerialManager;

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

    // Fragment tags — used so config-change restores can reattach to the existing
    // instances instead of losing state to freshly-constructed replacements.
    private static final String TAG_CONNECT     = "connect";
    private static final String TAG_KEYLOAD     = "keyload";
    private static final String TAG_CONTAINER   = "container";
    private static final String TAG_RADIO_KEYS  = "radio_keys";
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
    private UsbRndisManager  usbRndisManager;
    private volatile UsbRndisUdpTransport usbRndisTransport;    // non-null once DHCP has run
    private volatile KfdFrameProtocol frameProtocol;
    private volatile AdapterProtocol  adapterProtocol;   // non-null = TWI connected
    private volatile DliConfig        dliConfig;         // non-null = DLI active
    private volatile Boolean          dliVerified;       // null=untested, false=failed, true=ready
    /** DLI config snapshot taken when a USB radio attaches, restored on detach. */
    private volatile DliConfig        preUsbDliConfig;
    /** True if the connected DLI radio replied to the model-check as an incompatible platform. */
    private volatile boolean          dliRadioIncompatible;

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

        // On config-change restore, the FragmentManager already brought our fragments
        // back and their state. Adopt those instances instead of creating fresh
        // (empty) ones — otherwise navigating away from a restored tab loses the
        // saved state on the very next replace().
        androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
        connectFragment   = (ConnectFragment)   fm.findFragmentByTag(TAG_CONNECT);
        keyloadFragment   = (KeyloadFragment)   fm.findFragmentByTag(TAG_KEYLOAD);
        containerFragment = (ContainerFragment) fm.findFragmentByTag(TAG_CONTAINER);
        radioKeysFragment = (RadioKeysFragment) fm.findFragmentByTag(TAG_RADIO_KEYS);
        if (connectFragment   == null) connectFragment   = new ConnectFragment();
        if (keyloadFragment   == null) keyloadFragment   = new KeyloadFragment();
        if (containerFragment == null) containerFragment = new ContainerFragment();
        if (radioKeysFragment == null) radioKeysFragment = new RadioKeysFragment();

        usbRndisManager = new UsbRndisManager(this);
        usbRndisManager.setListener(rndisListener);
        // If an APX radio is already plugged in at launch, try to attach.
        android.hardware.usb.UsbDevice preAttached = usbRndisManager.findApxRadio();
        if (preAttached != null) {
            Log.i(TAG, "APX radio already attached at launch: " + preAttached.getProductName());
            usbRndisManager.requestPermissionAndOpen(preAttached);
        }

        if (savedInstanceState == null) showFragment(connectFragment, TAG_CONNECT);

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if      (id == R.id.nav_connect)    showFragment(connectFragment,   TAG_CONNECT);
            else if (id == R.id.nav_keyload)    showFragment(keyloadFragment,   TAG_KEYLOAD);
            else if (id == R.id.nav_container)  showFragment(containerFragment, TAG_CONTAINER);
            else if (id == R.id.nav_radio_keys) showFragment(radioKeysFragment, TAG_RADIO_KEYS);
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
                showFragment(containerFragment, TAG_CONTAINER);
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
        if (usbRndisManager != null) usbRndisManager.destroy();
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
        this.dliConfig            = new DliConfig(host, port, motVariant);
        this.dliVerified          = null;
        this.dliRadioIncompatible = false;
        UsbRndisUdpTransport t = usbRndisTransport;
        if (t != null) t.setTargetPort(port);
        connectFragment.refreshState();
    }

    public void testDliConnection(KeyloadCallback callback) {
        if (dliConfig == null) {
            runOnUiThread(() -> callback.onResult(false, "DLI not configured", null));
            return;
        }
        executor.execute(() -> {
            boolean viaUsb = usbRndisTransport != null;
            if (!viaUsb) {
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
            }
            final String  host = viaUsb ? getUsbRadioIp() : dliConfig.host;
            final int     port = dliConfig.port;
            final boolean mot  = dliConfig.motVariant;
            runOnUiThread(() -> connectFragment.appendLog(
                    "Connecting to " + host + ":" + port + (mot ? " (Motorola)" : " (Standard)")
                    + (viaUsb ? " over USB…" : "…")));
            UdpTransport udp = dliTransport(dliConfig);
            DataLinkIndependentProtocol proto = new DataLinkIndependentProtocol(udp, mot);
            try {
                proto.initSession();
                proto.endSession();
                runModelCheck();
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

    private static final int MODEL_CHECK_TCP_PORT = 8002;
    private static final int MODEL_CHECK_TIMEOUT_MS = 10000;
    private static final byte[] MODEL_CHECK_CMD = {0x00, 0x03, 0x00, 0x0F, 0x67};

    /**
     * Radio model diagnostic — TCP request to port 8002. Independent of the DLI
     * KMM session; can be run after endSession. Dispatches by transport:
     *   USB path → userspace one-shot TCP over RNDIS
     *   BT PAN path → java.net.Socket bound to the BT network
     */
    private void runModelCheck() {
        try {
            byte[] rsp;
            if (usbRndisTransport != null) {
                rsp = usbRndisTransport.oneShotTcp(MODEL_CHECK_TCP_PORT,
                        MODEL_CHECK_CMD, MODEL_CHECK_TIMEOUT_MS);
            } else if (dliConfig != null) {
                rsp = tcpRequestOverSocket(dliConfig.host, MODEL_CHECK_TCP_PORT,
                        MODEL_CHECK_CMD, MODEL_CHECK_TIMEOUT_MS);
            } else {
                return;
            }
            classifyModelResponse(rsp);
        } catch (Exception e) {
            Log.w(TAG, "Model check failed", e);
            runOnUiThread(() -> connectFragment.appendLog(
                    "DLI: Model check failed: " + e.getMessage()));
        }
    }

    private void classifyModelResponse(byte[] rsp) {
        String hex = bytesToHex(rsp);
        Log.i(TAG, "Model check response: " + hex);
        if (rsp.length == 0) {
            runOnUiThread(() -> connectFragment.appendLog(
                    "DLI: Model check — empty response."));
            return;
        }
        byte last = rsp[rsp.length - 1];
        if (last == 0x0A) {
            dliRadioIncompatible = false;
            runOnUiThread(() -> connectFragment.appendLog("DLI: Freon radio detected."));
        } else if (last == 0x07) {
            dliRadioIncompatible = true;
            runOnUiThread(() -> {
                connectFragment.appendLog(
                        "DLI: Incompatible radio (AN/Mackinaw/OMAP) — keyload blocked.");
                connectFragment.showIncompatibleRadioDialog();
            });
        } else {
            dliRadioIncompatible = false;
            runOnUiThread(() -> connectFragment.appendLog(
                    "DLI: Unknown radio model — response: " + hex));
        }
    }

    /**
     * Blocking TCP round-trip using the OS socket stack, bound to whichever
     * Network can route to {@code host} (Bluetooth PAN, typically).
     */
    private byte[] tcpRequestOverSocket(String host, int port, byte[] payload, int timeoutMs)
            throws IOException {
        InetAddress target = InetAddress.getByName(host);
        java.net.Socket sock = new java.net.Socket();
        try {
            bindSocketToRoute(sock, target);
            sock.connect(new java.net.InetSocketAddress(target, port), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            sock.getOutputStream().write(payload);
            sock.getOutputStream().flush();
            java.io.InputStream in = sock.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int n;
                try { n = in.read(buf); }
                catch (java.net.SocketTimeoutException te) { break; }
                if (n < 0) break;
                out.write(buf, 0, n);
                // The radio's model reply is a single short segment; stop as soon as we have it.
                if (out.size() >= 4) break;
            }
            return out.toByteArray();
        } finally {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    /** Bind {@code sock} to the Network whose link addresses cover {@code target}. */
    private void bindSocketToRoute(java.net.Socket sock, InetAddress target) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            byte[] targetBytes = target.getAddress();
            for (Network net : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) continue;
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    if (!(la.getAddress() instanceof java.net.Inet4Address)) continue;
                    if (sameSubnet(targetBytes, la.getAddress().getAddress(), la.getPrefixLength())) {
                        net.bindSocket(sock);
                        return;
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private static boolean sameSubnet(byte[] a, byte[] b, int prefix) {
        if (prefix <= 0 || prefix > 32) return false;
        int mask = (prefix == 32) ? 0xFFFFFFFF : (0xFFFFFFFF << (32 - prefix));
        int ai = ((a[0]&0xFF)<<24) | ((a[1]&0xFF)<<16) | ((a[2]&0xFF)<<8) | (a[3]&0xFF);
        int bi = ((b[0]&0xFF)<<24) | ((b[1]&0xFF)<<16) | ((b[2]&0xFF)<<8) | (b[3]&0xFF);
        return (ai & mask) == (bi & mask);
    }

    /** Called on DLI tab switch: silently runs a test only if the configured IP is routable. */
    public void autoTestDli() {
        if (dliConfig == null) return;
        executor.execute(() -> {
            boolean viaUsb = usbRndisTransport != null;
            if (!viaUsb && !isHostRoutable(dliConfig.host)) return;
            final String  host = viaUsb ? getUsbRadioIp() : dliConfig.host;
            final int     port = dliConfig.port;
            final boolean mot  = dliConfig.motVariant;
            runOnUiThread(() -> connectFragment.appendLog(
                    "Connecting to " + host + ":" + port + (mot ? " (Motorola)" : " (Standard)")
                    + (viaUsb ? " over USB…" : "…")));
            UdpTransport udp = dliTransport(dliConfig);
            DataLinkIndependentProtocol proto = new DataLinkIndependentProtocol(udp, mot);
            try {
                proto.initSession();
                proto.endSession();
                runModelCheck();
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
        this.dliConfig            = null;
        this.dliVerified          = null;
        this.dliRadioIncompatible = false;
        connectFragment.refreshState();
    }

    public boolean isDliRadioIncompatible() { return dliRadioIncompatible; }

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
            return new ManualRekeyApplication(dliTransport(cfg), cfg.motVariant);
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
        if (dliConfig != null && dliRadioIncompatible) {
            runOnUiThread(() -> callback.onResult(false,
                    "Radio is not compatible with software keyloading (AN/Mackinaw/OMAP). "
                    + "Use hardware keyloading over TWI.", null));
            return;
        }
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

    // ── USB RNDIS (APX radio) listener ────────────────────────────────────────

    private final UsbRndisManager.Listener rndisListener = new UsbRndisManager.Listener() {
        @Override public void onAttached(UsbDevice d) {
            String productName = d.getProductName();
            Log.i(TAG, "RNDIS attached: " + productName
                    + " VID=0x" + Integer.toHexString(d.getVendorId())
                    + " PID=0x" + Integer.toHexString(d.getProductId()));

            // Motorola's generic "LTD" device descriptor turns up on radios whose
            // USB personality isn't configured for DLI. It presents like our target
            // but doesn't speak RNDIS — surface a clear message instead of hanging.
            if (productName != null && productName.contains("Motorola Solutions LTD Device")) {
                Log.w(TAG, "Rejecting unsupported device: " + productName);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("Unsupported Radio")
                            .setMessage("The connected radio is not supported. Connect a supported radio.")
                            .setPositiveButton("OK", null)
                            .show();
                });
                return;
            }

            // Snapshot current DLI config so we can put things back exactly as the
            // user left them when the USB radio is unplugged.
            preUsbDliConfig = dliConfig;

            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                        "USB radio attached — starting DLI…", Toast.LENGTH_SHORT).show();
                if (connectFragment != null)
                    connectFragment.appendLog("USB radio attached: " + productName);
            });
            executor.execute(() -> {
                try {
                    int port = dliConfig != null ? dliConfig.port : 49644;
                    UsbRndisUdpTransport t = new UsbRndisUdpTransport(
                            usbRndisManager, port, DLI_TIMEOUT);
                    t.bringUp();
                    usbRndisTransport = t;
                    Log.i(TAG, "USB DLI up (radio IP=" + t.getRadioIp() + ")");
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "USB DLI ready — radio at " + t.getRadioIp(),
                                Toast.LENGTH_LONG).show();
                        if (connectFragment != null) {
                            connectFragment.appendLog("USB DLI ready — radio at " + t.getRadioIp());
                            connectFragment.switchToDliMode();
                            connectFragment.refreshState();
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "USB RNDIS bring-up failed", e);
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "USB DLI failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        if (connectFragment != null) connectFragment.appendLog(
                                "USB DLI bring-up failed: " + e.getMessage());
                    });
                }
            });
        }
        @Override public void onDetached() {
            usbRndisTransport = null;
            // A DLI session over the now-gone USB link is no longer verified;
            // clear so isConnected() reflects reality and keyload buttons disable.
            dliVerified = null;
            dliRadioIncompatible = false;
            // Restore the DLI config that was active before the USB radio was
            // plugged in so the user's saved BT PAN setup is preserved.
            if (preUsbDliConfig != null) {
                dliConfig = preUsbDliConfig;
                preUsbDliConfig = null;
            }
            Log.i(TAG, "RNDIS detached");
            runOnUiThread(() -> {
                if (connectFragment   != null) {
                    connectFragment.appendLog("USB radio detached.");
                    connectFragment.refreshState();
                }
                if (keyloadFragment   != null) keyloadFragment.refreshButtons();
                if (containerFragment != null) containerFragment.updateLoadButton();
                if (radioKeysFragment != null) radioKeysFragment.refreshButtons();
            });
        }
        @Override public void onError(String message) {
            Log.w(TAG, "RNDIS error: " + message);
            runOnUiThread(() -> {
                if (connectFragment != null)
                    connectFragment.appendLog("USB radio: " + message);
            });
        }
    };

    /** True if an APX radio has been brought up over USB and DHCP has completed. */
    public boolean isUsbRndisReady() { return usbRndisTransport != null; }

    /** IP the radio gave us via DHCP (for display only). */
    public String getUsbRadioIp() {
        UsbRndisUdpTransport t = usbRndisTransport;
        return t == null ? null : t.getRadioIp();
    }

    /**
     * Chooses the right DLI transport. Prefers USB when a radio is attached,
     * otherwise falls back to a socket-based UdpProtocol using the configured host/port.
     */
    private UdpTransport dliTransport(DliConfig cfg) {
        UsbRndisUdpTransport t = usbRndisTransport;
        if (t != null) return t;
        return new UdpProtocol(cfg.host, cfg.port, DLI_TIMEOUT, this);
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
                        // Replace the USB descriptor name (e.g. "USB Serial Device") with
                        // the identified KFD adapter type in the green connection banner.
                        connectFragment.setDeviceName(typeName);
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
            if (device == null) {
                Log.w(TAG, "USB_DEVICE_ATTACHED with no EXTRA_DEVICE");
                return;
            }
            Log.i(TAG, "USB_DEVICE_ATTACHED VID=0x" + Integer.toHexString(device.getVendorId())
                    + " PID=0x" + Integer.toHexString(device.getProductId())
                    + " name=" + device.getProductName());
            if (UsbRndisManager.isApxRadio(device)) {
                usbRndisManager.requestPermissionAndOpen(device);
            } else {
                usbManager.requestPermission();
            }
        }
    }

    private void showFragment(Fragment fragment, String tag) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment, tag)
                .commit();
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
