/*
 * Copyright 2026 Charles Bricker
 * Licensed under the MIT License (see project root).
 */
package com.sentinelcomms.kfddroid.usb.rndis;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;

/**
 * Owns the USB connection to an APX radio's RNDIS interface.
 * Separate from {@link com.sentinelcomms.kfddroid.usb.UsbSerialManager} — they can coexist
 * because they match different VID:PID sets.
 *
 * <p>The APX (VID:PID 0cad:1052) exposes:
 * <ul>
 *   <li>Interface 0: class 2/2/255 (RNDIS control) with interrupt IN endpoint</li>
 *   <li>Interface 1: class 10/0/0 (CDC Data) with bulk IN and bulk OUT</li>
 * </ul>
 * Control transfers use EP0 with class request 0x21/0x00 (send) and 0xA1/0x01 (get).
 */
public class UsbRndisManager {

    private static final String TAG = "UsbRndisManager";
    private static final String ACTION_USB_PERMISSION = "com.sentinelcomms.kfddroid.RNDIS_USB_PERMISSION";

    /** Motorola Solutions USB VID (used across all APX Series Radios). */
    public static final int APX_VID = 0x0CAD;
    /** Known APX PIDs. We match by VID only + interface layout; this is informational.
     *  0x101B = mobile control head, 0x1051 / 0x1052 = portable radios. */
    public static final int[] KNOWN_APX_PIDS = { 0x101B, 0x1051, 0x1052 };

    /** True if the device looks like an APX radio (Motorola VID + RNDIS-shaped interfaces). */
    public static boolean isApxRadio(UsbDevice d) {
        return d != null && d.getVendorId() == APX_VID;
    }

    /** RNDIS control-message request type for EP0: host→device, class, interface. */
    private static final int RT_HOST_TO_DEV = UsbConstants.USB_DIR_OUT
            | UsbConstants.USB_TYPE_CLASS | 0x01;
    /** RNDIS control-message request type for EP0: device→host, class, interface. */
    private static final int RT_DEV_TO_HOST = UsbConstants.USB_DIR_IN
            | UsbConstants.USB_TYPE_CLASS | 0x01;

    public interface Listener {
        void onAttached(UsbDevice device);
        void onDetached();
        void onError(String message);
    }

    private final Context      context;
    private final UsbManager   usbManager;
    private       Listener     listener;

    private UsbDevice           device;
    private UsbDeviceConnection connection;
    private UsbInterface        controlIface;
    private UsbInterface        dataIface;
    private UsbEndpoint         bulkIn;
    private UsbEndpoint         bulkOut;
    private UsbEndpoint         notifyIn;      // interrupt IN on control iface (RNDIS "response available")

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && d != null) {
                try {
                    openDevice(d);
                } catch (IOException e) {
                    fail("Open failed: " + e.getMessage());
                }
            } else {
                fail("USB permission denied");
            }
        }
    };

    /** Fires whenever any USB device is unplugged; we filter for our current one. */
    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) return;
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d == null || device == null) return;
            if (d.getDeviceId() == device.getDeviceId()) {
                Log.i(TAG, "APX radio detached (deviceId=" + d.getDeviceId() + ")");
                close();
            }
        }
    };

    public UsbRndisManager(Context context) {
        this.context    = context.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            this.context.registerReceiver(permissionReceiver, filter);
        }

        // ACTION_USB_DEVICE_DETACHED is a system broadcast — always allowed.
        this.context.registerReceiver(detachReceiver,
                new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
    }

    public void setListener(Listener listener) { this.listener = listener; }

    /** Returns a currently-attached APX radio, or null if none is plugged in. */
    public UsbDevice findApxRadio() {
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (isApxRadio(d)) return d;
        }
        return null;
    }

    /** Request permission for {@code d}; permissionReceiver opens it on grant. */
    public void requestPermissionAndOpen(UsbDevice d) {
        if (isOpen()) return;
        if (usbManager.hasPermission(d)) {
            try { openDevice(d); }
            catch (IOException e) { fail("Open failed: " + e.getMessage()); }
            return;
        }
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()), flags);
        usbManager.requestPermission(d, pi);
    }

    private void openDevice(UsbDevice d) throws IOException {
        UsbInterface ctrl = null, data = null;
        UsbEndpoint  in   = null, out  = null;

        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            // Control iface: class 2 (comm), subclass 2 (ACM).
            // Protocol byte varies by APX firmware — 255 (vendor / RNDIS) on some, 0 on others.
            // Both encode the same RNDIS control model; accept any protocol here.
            if (ctrl == null
                    && itf.getInterfaceClass() == 2
                    && itf.getInterfaceSubclass() == 2) {
                ctrl = itf;
            }
            // Data iface: class 10 (CDC data), any subclass
            if (data == null && itf.getInterfaceClass() == 10) {
                data = itf;
            }
        }
        if (ctrl == null || data == null) {
            throw new IOException("Device does not expose RNDIS control+data interfaces");
        }
        for (int i = 0; i < data.getEndpointCount(); i++) {
            UsbEndpoint ep = data.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN)  in  = ep;
            if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep;
        }
        if (in == null || out == null) {
            throw new IOException("Data interface missing bulk endpoints");
        }

        // Interrupt-IN on the control interface = RNDIS "response available" notification.
        UsbEndpoint notify = null;
        for (int i = 0; i < ctrl.getEndpointCount(); i++) {
            UsbEndpoint ep = ctrl.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                    && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                notify = ep;
                break;
            }
        }

        UsbDeviceConnection conn = usbManager.openDevice(d);
        if (conn == null) throw new IOException("openDevice returned null (permission?)");
        if (!conn.claimInterface(ctrl, true)) {
            conn.close();
            throw new IOException("Failed to claim RNDIS control interface");
        }
        if (!conn.claimInterface(data, true)) {
            conn.releaseInterface(ctrl);
            conn.close();
            throw new IOException("Failed to claim CDC data interface");
        }

        this.device       = d;
        this.connection   = conn;
        this.controlIface = ctrl;
        this.dataIface    = data;
        this.bulkIn       = in;
        this.bulkOut      = out;
        this.notifyIn     = notify;

        // Windows RNDIS driver issues SET_INTERFACE(alt=0) on both interfaces after claim.
        // Some firmwares won't route bulk / interrupt traffic until they see this — issue it
        // ourselves. Failures here are non-fatal (some devices don't require it).
        setInterfaceAltSetting(conn, ctrl.getId(), 0);
        setInterfaceAltSetting(conn, data.getId(), 0);

        Log.i(TAG, "Opened APX RNDIS: " + d.getProductName()
                + " control-if=" + ctrl.getId() + " data-if=" + data.getId()
                + " notifyEp=" + (notify == null ? "none" : "0x" + Integer.toHexString(notify.getAddress())));
        if (listener != null) listener.onAttached(d);
    }

    public boolean isOpen() { return connection != null; }

    public UsbDevice getDevice() { return device; }

    public void close() {
        if (connection != null) {
            if (controlIface != null) connection.releaseInterface(controlIface);
            if (dataIface    != null) connection.releaseInterface(dataIface);
            connection.close();
        }
        connection = null;
        device = null;
        controlIface = null;
        dataIface = null;
        bulkIn = null;
        bulkOut = null;
        notifyIn = null;
        if (listener != null) listener.onDetached();
    }

    public void destroy() {
        try { context.unregisterReceiver(permissionReceiver); }
        catch (IllegalArgumentException ignored) {}
        try { context.unregisterReceiver(detachReceiver); }
        catch (IllegalArgumentException ignored) {}
        close();
    }

    // ── RNDIS transport (called by RndisSession) ──────────────────────────────

    /** RNDIS SEND_ENCAPSULATED_COMMAND on EP0. */
    int controlOut(byte[] data, int timeoutMs) throws IOException {
        if (connection == null) throw new IOException("Not open");
        int wIndex = controlIface.getId();
        int n = connection.controlTransfer(RT_HOST_TO_DEV, Rndis.REQ_SEND_ENCAPSULATED,
                0, wIndex, data, data.length, timeoutMs);
        if (n < 0) throw new IOException("controlTransfer OUT failed (" + n + ")");
        return n;
    }

    /** RNDIS GET_ENCAPSULATED_RESPONSE on EP0. */
    int controlIn(byte[] buffer, int timeoutMs) throws IOException {
        if (connection == null) throw new IOException("Not open");
        int wIndex = controlIface.getId();
        int n = connection.controlTransfer(RT_DEV_TO_HOST, Rndis.REQ_GET_ENCAPSULATED,
                0, wIndex, buffer, buffer.length, timeoutMs);
        if (n < 0) throw new IOException("controlTransfer IN failed (" + n + ")");
        return n;
    }

    int bulkOut(byte[] data, int timeoutMs) throws IOException {
        if (connection == null) throw new IOException("Not open");
        int n = connection.bulkTransfer(bulkOut, data, data.length, timeoutMs);
        if (n < 0) throw new IOException("bulkTransfer OUT failed");
        return n;
    }

    int bulkIn(byte[] buffer, int timeoutMs) throws IOException {
        if (connection == null) throw new IOException("Not open");
        int n = connection.bulkTransfer(bulkIn, buffer, buffer.length, timeoutMs);
        if (n < 0) throw new IOException("bulkTransfer IN failed (timeout?)");
        return n;
    }

    /**
     * Block until an RNDIS "response available" interrupt fires on the control
     * interface (or timeout). Returns true when notified. If the device has no
     * interrupt endpoint, returns true immediately so RndisSession can just poll.
     */
    boolean waitForResponseNotification(int timeoutMs) throws IOException {
        if (connection == null) throw new IOException("Not open");
        if (notifyIn == null) return true;
        byte[] buf = new byte[notifyIn.getMaxPacketSize()];
        int n = connection.bulkTransfer(notifyIn, buf, buf.length, timeoutMs);
        return n >= 0;
    }

    /** Standard USB SET_INTERFACE(interface, alternate) request on EP0. */
    private static void setInterfaceAltSetting(UsbDeviceConnection conn, int ifaceId, int alt) {
        // bmRequestType=0x01 (out, standard, interface), bRequest=0x0B, wValue=alt, wIndex=iface
        conn.controlTransfer(0x01, 0x0B, alt, ifaceId, null, 0, 500);
    }

    private void fail(String message) {
        Log.w(TAG, message);
        if (listener != null) listener.onError(message);
    }
}
