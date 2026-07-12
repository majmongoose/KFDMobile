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
package com.sentinelcomms.kfddroid.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;

/**
 * Manages USB OTG serial connection to the KFD Shield/Tool adapter.
 * Wraps usb-serial-for-android to provide open/close/read/write.
 */
public class UsbSerialManager {

    private static final String TAG = "UsbSerialManager";
    private static final String ACTION_USB_PERMISSION = "com.sentinelcomms.kfddroid.USB_PERMISSION";

    public interface ConnectionListener {
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String message);
        void onPermissionGranted(UsbDevice device);
        void onPermissionDenied();
    }

    private final Context context;
    private final UsbManager usbManager;
    private UsbSerialPort serialPort;
    private UsbDeviceConnection connection;
    private ConnectionListener listener;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    if (listener != null) listener.onPermissionGranted(device);
                } else {
                    if (listener != null) listener.onPermissionDenied();
                }
            }
        }
    };

    public UsbSerialManager(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(permissionReceiver, filter);
        }
    }

    public void setListener(ConnectionListener listener) {
        this.listener = listener;
    }

    /** Find the first compatible USB serial device and request permission. */
    public void requestPermission() {
        if (isOpen()) return; // already connected — ignore duplicate taps
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            if (listener != null) listener.onError(context.getString(
                    com.sentinelcomms.kfddroid.R.string.error_no_serial_device));
            return;
        }
        UsbDevice device = drivers.get(0).getDevice();
        if (usbManager.hasPermission(device)) {
            if (listener != null) listener.onPermissionGranted(device);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()), flags);
            usbManager.requestPermission(device, pi);
        }
    }

    /** Open the serial port at 115200 8N1. Blocks briefly. */
    public boolean open(UsbDevice device) throws IOException {
        if (isOpen()) return true; // guard against concurrent open calls
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        UsbSerialDriver driver = null;
        for (UsbSerialDriver d : drivers) {
            if (d.getDevice().equals(device)) {
                driver = d;
                break;
            }
        }
        if (driver == null) {
            // Fallback: use first available driver
            if (!drivers.isEmpty()) driver = drivers.get(0);
        }
        if (driver == null) return false;

        connection = usbManager.openDevice(driver.getDevice());
        if (connection == null) {
            if (listener != null) listener.onError(context.getString(
                    com.sentinelcomms.kfddroid.R.string.error_usb_permission_denied));
            return false;
        }

        serialPort = driver.getPorts().get(0);
        serialPort.open(connection);
        serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

        String name = driver.getDevice().getProductName();
        if (name == null) name = "USB Serial Device";
        if (listener != null) listener.onConnected(name);
        Log.i(TAG, "Opened serial port: " + name);
        return true;
    }

    /** Close the serial port. */
    public void close() {
        if (serialPort != null) {
            try { serialPort.close(); } catch (IOException e) { Log.w(TAG, "close error", e); }
            serialPort = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
        if (listener != null) listener.onDisconnected();
    }

    /** Write raw bytes. Must be called from a background thread. */
    public void write(byte[] data, int timeoutMs) throws IOException {
        if (serialPort == null) throw new IOException("Serial port not open");
        serialPort.write(data, timeoutMs);
    }

    /**
     * Read up to maxLen bytes with the given timeout.
     * Returns number of bytes actually read. Must be called from a background thread.
     */
    public int read(byte[] buffer, int timeoutMs) throws IOException {
        if (serialPort == null) throw new IOException("Serial port not open");
        return serialPort.read(buffer, timeoutMs);
    }

    public boolean isOpen() {
        return serialPort != null;
    }

    public void destroy() {
        try {
            context.unregisterReceiver(permissionReceiver);
        } catch (IllegalArgumentException ignored) {}
        close();
    }
}
