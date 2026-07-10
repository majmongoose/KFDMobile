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
package com.kfdtool.android.protocol;

import android.util.Log;

import com.kfdtool.android.usb.UsbSerialManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Implements the KFD serial framing protocol in either of two variants:
 *
 * KFD_SHIELD (KFDShield / Other adapters):
 *   SOM=0x61, EOM=0x63, ESC=0x70, ESC_PLACEHOLDER=0x71,
 *   SOM_PLACEHOLDER=0x62, EOM_PLACEHOLDER=0x64
 *
 * KFD_TOOL (Original KFDTool):
 *   SOM_EOM=0x61 (start and end share the same byte), ESC=0x63,
 *   ESC_PLACEHOLDER=0x64, SOM_EOM_PLACEHOLDER=0x62
 *
 * Use autoDetect() to probe which variant the connected device uses.
 */
public class KfdFrameProtocol {

    private static final String TAG = "KfdFrameProtocol";

    /** Hardware adapter framing variant. */
    public enum AdapterType {
        /**
         * Original KFDTool — SOM and EOM are both 0x61, ESC=0x63.
         * Probed first: its framing (EOM=0x61) is a no-op SOM reset for KFDShield devices,
         * so probing KFD_TOOL first never corrupts the other device's receive buffer.
         * KFDShield's EOM (0x63) is the KFDTool ESC byte, which leaves junk in the
         * KFDTool buffer if we probe KFDShield first.
         */
        KFD_TOOL,
        /** KFDShield / Other — distinct SOM(0x61) and EOM(0x63), ESC=0x70. */
        KFD_SHIELD
    }

    // KFDShield constants
    private static final byte SHIELD_SOM             = 0x61;
    private static final byte SHIELD_SOM_PLACEHOLDER = 0x62;
    private static final byte SHIELD_EOM             = 0x63;
    private static final byte SHIELD_EOM_PLACEHOLDER = 0x64;
    private static final byte SHIELD_ESC             = 0x70;
    private static final byte SHIELD_ESC_PLACEHOLDER = 0x71;

    // Original KFDTool constants
    private static final byte TOOL_SOM_EOM             = 0x61;
    private static final byte TOOL_SOM_EOM_PLACEHOLDER = 0x62;
    private static final byte TOOL_ESC                 = 0x63;
    private static final byte TOOL_ESC_PLACEHOLDER     = 0x64;

    // Adapter broadcasts a received radio byte with this opcode.
    private static final byte BCST_RECEIVE_BYTE = 0x31;

    private static final int READ_BUFFER_SIZE    = 1024;
    private static final int USB_READ_TIMEOUT_MS = 50;

    // AP version command/response bytes used for auto-detect probing
    private static final byte PROBE_CMD  = 0x11; // CMD_READ
    private static final byte PROBE_SUB  = 0x01; // READ_AP_VER
    private static final byte PROBE_RSP  = 0x21; // RSP_READ

    private final UsbSerialManager usbManager;
    private final AdapterType adapterType;

    // Command responses (everything except BCST_RECEIVE_BYTE)
    private final LinkedBlockingQueue<byte[]> responseQueue  = new LinkedBlockingQueue<>();
    // Radio byte broadcasts (BCST_RECEIVE_BYTE packets)
    private final LinkedBlockingQueue<byte[]> broadcastQueue = new LinkedBlockingQueue<>();

    private Thread receiveThread;
    private volatile boolean running = false;

    private final List<Byte> frameBuffer = new ArrayList<>();
    private boolean foundStart = false;

    public KfdFrameProtocol(UsbSerialManager usbManager, AdapterType type) {
        this.usbManager  = usbManager;
        this.adapterType = type;
    }

    public AdapterType getAdapterType() { return adapterType; }

    // ── Auto-detection ────────────────────────────────────────────────────────

    /**
     * Probes the connected device and returns a KfdFrameProtocol configured for
     * the detected framing variant, or null if neither responds.
     *
     * Probe order: KFD_TOOL first, KFD_SHIELD second.
     *
     * Why this order matters:
     *   KFD_TOOL probe EOM = 0x61 (SOM for KFDShield) — KFDShield just sets
     *   foundStart=true again without clearing its buffer.  A bare 0x63 flush
     *   byte sent before the KFDShield probe forces the device to dispatch
     *   (and discard) whatever is left in its buffer, giving it a clean slate.
     *
     *   KFDShield probe EOM = 0x63 (ESC for KFDTool) — sending it first would
     *   park an escape byte at the end of the KFDTool buffer, causing the
     *   subsequent KFDTool probe to arrive garbled.  By probing KFD_TOOL first
     *   we avoid that corruption entirely.
     */
    public static KfdFrameProtocol autoDetect(UsbSerialManager usbManager, int probeTimeoutMs)
            throws InterruptedException {
        // --- KFD_TOOL probe ---
        KfdFrameProtocol fp = new KfdFrameProtocol(usbManager, AdapterType.KFD_TOOL);
        fp.startReceiving();
        if (fp.probe(probeTimeoutMs)) return fp;
        fp.stopReceiving();

        // --- KFD_SHIELD probe ---
        // The KFD_TOOL probe left [CMD, SUB] sitting in the KFDShield device's
        // frame buffer (it saw two SOMs but no EOM).  Send a bare EOM (0x63)
        // so the firmware dispatches that leftover data and clears its buffer.
        // Then wait briefly for any spurious response, clear our queue, and probe.
        fp = new KfdFrameProtocol(usbManager, AdapterType.KFD_SHIELD);
        fp.startReceiving();
        try { usbManager.write(new byte[]{SHIELD_EOM}, 200); } catch (IOException ignored) {}
        Thread.sleep(80);
        fp.clear();
        if (fp.probe(probeTimeoutMs)) return fp;
        fp.stopReceiving();

        return null;
    }

    /**
     * Sends an AP-version request and returns true if a valid response arrives
     * within timeoutMs. Used internally by autoDetect().
     */
    public boolean probe(int timeoutMs) throws InterruptedException {
        try {
            List<Byte> cmd = new ArrayList<>();
            cmd.add(PROBE_CMD);
            cmd.add(PROBE_SUB);
            clear();
            send(cmd);
            List<Byte> rsp = read(timeoutMs);
            return rsp.size() == 5
                    && (rsp.get(0) & 0xFF) == (PROBE_RSP & 0xFF)
                    && (rsp.get(1) & 0xFF) == (PROBE_SUB & 0xFF);
        } catch (IOException e) {
            return false;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Start background receive loop. */
    public void startReceiving() {
        running = true;
        receiveThread = new Thread(this::receiveLoop, "kfd-receive");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    /** Stop background receive loop. */
    public void stopReceiving() {
        running = false;
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        responseQueue.clear();
        broadcastQueue.clear();
        frameBuffer.clear();
        foundStart = false;
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /** Send a packet. Applies framing and byte escaping for the current adapter type. */
    public synchronized void send(List<Byte> data) throws IOException {
        List<Byte> frame = new ArrayList<>();
        if (adapterType == AdapterType.KFD_TOOL) {
            buildFrameKfdTool(data, frame);
        } else {
            buildFrameKfdShield(data, frame);
        }
        byte[] outData = toByteArray(frame);
        Log.d(TAG, "TX [" + adapterType + "] " + outData.length + " bytes: " + toHex(outData));
        usbManager.write(outData, 2000);
    }

    private static void buildFrameKfdShield(List<Byte> data, List<Byte> frame) {
        frame.add(SHIELD_SOM);
        for (byte b : data) {
            if (b == SHIELD_ESC) {
                frame.add(SHIELD_ESC);
                frame.add(SHIELD_ESC_PLACEHOLDER);
            } else if (b == SHIELD_SOM) {
                frame.add(SHIELD_ESC);
                frame.add(SHIELD_SOM_PLACEHOLDER);
            } else if (b == SHIELD_EOM) {
                frame.add(SHIELD_ESC);
                frame.add(SHIELD_EOM_PLACEHOLDER);
            } else {
                frame.add(b);
            }
        }
        frame.add(SHIELD_EOM);
    }

    private static void buildFrameKfdTool(List<Byte> data, List<Byte> frame) {
        frame.add(TOOL_SOM_EOM);
        for (byte b : data) {
            if (b == TOOL_ESC) {
                frame.add(TOOL_ESC);
                frame.add(TOOL_ESC_PLACEHOLDER);
            } else if (b == TOOL_SOM_EOM) {
                frame.add(TOOL_ESC);
                frame.add(TOOL_SOM_EOM_PLACEHOLDER);
            } else {
                frame.add(b);
            }
        }
        frame.add(TOOL_SOM_EOM);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Block until a command-response packet arrives or timeout elapses.
     * BCST_RECEIVE_BYTE packets are routed to broadcastQueue and never returned here.
     *
     * @param timeoutMs milliseconds to wait; 0 = wait indefinitely
     */
    public List<Byte> read(int timeoutMs) throws IOException, InterruptedException {
        byte[] packet;
        if (timeoutMs > 0) {
            packet = responseQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (packet == null) throw new IOException("Timeout waiting for response");
        } else {
            packet = responseQueue.take();
        }
        return toList(packet);
    }

    /**
     * Block until a BCST_RECEIVE_BYTE packet arrives or timeout elapses.
     *
     * @param timeoutMs milliseconds to wait; 0 = wait indefinitely
     */
    public List<Byte> readBroadcast(int timeoutMs) throws IOException, InterruptedException {
        byte[] packet;
        if (timeoutMs > 0) {
            packet = broadcastQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (packet == null) throw new IOException("Timeout waiting for broadcast byte");
        } else {
            packet = broadcastQueue.take();
        }
        return toList(packet);
    }

    /** Drain all buffered packets (call before starting a new command sequence). */
    public void clear() {
        responseQueue.clear();
        broadcastQueue.clear();
        frameBuffer.clear();
        foundStart = false;
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private void receiveLoop() {
        byte[] buf = new byte[READ_BUFFER_SIZE];
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                int n = usbManager.read(buf, USB_READ_TIMEOUT_MS);
                if (n > 0) {
                    Log.d(TAG, "RX [" + adapterType + "] " + n + " bytes");
                    processBytes(buf, n);
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "Read error: " + e.getMessage());
                break;
            }
        }
    }

    private void processBytes(byte[] data, int len) {
        if (adapterType == AdapterType.KFD_TOOL) {
            processBytesKfdTool(data, len);
        } else {
            processBytesKfdShield(data, len);
        }
    }

    private void processBytesKfdShield(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            byte b = data[i];
            if (b == SHIELD_SOM) {
                foundStart = true;
                frameBuffer.clear();
            } else if (b == SHIELD_EOM) {
                if (!foundStart) continue;
                dispatchFrame(frameBuffer, false);
                frameBuffer.clear();
                foundStart = false;
            } else {
                if (foundStart) frameBuffer.add(b);
            }
        }
    }

    private void processBytesKfdTool(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            byte b = data[i];
            if (b == TOOL_SOM_EOM) {
                // A second 0x61 with buffered data = EOM; an empty buffer = SOM.
                foundStart = true;
                if (!frameBuffer.isEmpty()) {
                    dispatchFrame(frameBuffer, true);
                    frameBuffer.clear();
                }
                // else: first SOM — buffer already empty, just mark foundStart
            } else {
                if (foundStart) frameBuffer.add(b);
            }
        }
    }

    /** Unescape and route a completed raw frame buffer. */
    private void dispatchFrame(List<Byte> raw, boolean kfdToolMode) {
        try {
            List<Byte> unescaped = kfdToolMode ? unescapeKfdTool(raw) : unescapeKfdShield(raw);
            byte[] packet = toByteArray(unescaped);
            Log.d(TAG, "Packet [" + adapterType + "]: " + toHex(packet));
            if (packet.length > 0 && packet[0] == BCST_RECEIVE_BYTE) {
                broadcastQueue.offer(packet);
            } else {
                responseQueue.offer(packet);
            }
        } catch (Exception e) {
            Log.w(TAG, "Frame parse error: " + e.getMessage());
        }
    }

    private static List<Byte> unescapeKfdShield(List<Byte> raw) {
        List<Byte> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            byte b = raw.get(i);
            if (b == SHIELD_ESC) {
                i++;
                if (i >= raw.size()) throw new IllegalArgumentException("ESC at end of frame");
                byte next = raw.get(i);
                if      (next == SHIELD_ESC_PLACEHOLDER) out.add(SHIELD_ESC);
                else if (next == SHIELD_SOM_PLACEHOLDER) out.add(SHIELD_SOM);
                else if (next == SHIELD_EOM_PLACEHOLDER) out.add(SHIELD_EOM);
                else throw new IllegalArgumentException(
                        "Invalid KFDShield ESC sequence: 0x" + String.format("%02X", next));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private static List<Byte> unescapeKfdTool(List<Byte> raw) {
        List<Byte> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            byte b = raw.get(i);
            if (b == TOOL_ESC) {
                i++;
                if (i >= raw.size()) throw new IllegalArgumentException("ESC at end of frame");
                byte next = raw.get(i);
                if      (next == TOOL_ESC_PLACEHOLDER)     out.add(TOOL_ESC);
                else if (next == TOOL_SOM_EOM_PLACEHOLDER) out.add(TOOL_SOM_EOM);
                else throw new IllegalArgumentException(
                        "Invalid KFDTool ESC sequence: 0x" + String.format("%02X", next));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static byte[] toByteArray(List<Byte> list) {
        byte[] arr = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static List<Byte> toList(byte[] arr) {
        List<Byte> list = new ArrayList<>(arr.length);
        for (byte b : arr) list.add(b);
        return list;
    }

    static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
}
