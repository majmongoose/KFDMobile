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
package com.sentinelcomms.kfdmobile.p25;

import com.sentinelcomms.kfdmobile.p25.kmm.InventoryResponseListActiveKsetIds;
import com.sentinelcomms.kfdmobile.p25.kmm.InventoryResponseListRsiItems;
import com.sentinelcomms.kfdmobile.p25.kmm.KeyItem;
import com.sentinelcomms.kfdmobile.p25.kmm.KeyStatus;
import com.sentinelcomms.kfdmobile.p25.kmm.KmmFrame;
import com.sentinelcomms.kfdmobile.p25.kmm.MessageId;
import com.sentinelcomms.kfdmobile.p25.kmm.ModifyKeyCommand;
import com.sentinelcomms.kfdmobile.p25.kmm.NegativeAcknowledgment;
import com.sentinelcomms.kfdmobile.p25.kmm.RekeyAcknowledgment;
import com.sentinelcomms.kfdmobile.protocol.AdapterProtocol;

import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MR (Mobile Radio) Emulator protocol.
 *
 * The phone acts as a radio — it waits for a KFD to initiate a session, responds to
 * the handshake, handles inventory queries and key-load commands, and reports received
 * keys to the UI via {@link Listener}.
 *
 * Call {@link #run()} on a dedicated background thread. Stop by calling {@link #stop()}
 * and then interrupting the thread.
 *
 * Mirrors P25MrEmulator / ThreeWireProtocol.MrRunProducer from the .NET source.
 */
public class MrEmulatorProtocol {

    private static final String TAG = "MrEmulator";
    private static final int TIMEOUT_STD = 5000; // ms

    private static final byte OPCODE_READY_REQ             = (byte) 0xC0;
    private static final byte OPCODE_READY_GENERAL_MODE_MR = (byte) 0xD0;
    private static final byte OPCODE_TRANSFER_DONE         = (byte) 0xC1;
    private static final byte OPCODE_KMM                   = (byte) 0xC2;
    private static final byte OPCODE_DISCONNECT            = (byte) 0x92;
    private static final byte OPCODE_DISCONNECT_ACK        = (byte) 0x90;

    private static final int INV_TYPE_LIST_ACTIVE_KSET_IDS = 0x02;
    private static final int INV_TYPE_LIST_RSI_ITEMS       = 0x0B;

    private final AdapterProtocol adapter;
    private volatile boolean running;
    private Listener listener;

    public interface Listener {
        void onStatus(String message);
        void onKeyReceived(int keysetId, int algId, int keyId, int sln, boolean erase, byte[] key);
        void onSessionStarted();
        void onSessionEnded();
    }

    public MrEmulatorProtocol(AdapterProtocol adapter) {
        this.adapter = adapter;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void stop() {
        running = false;
    }

    /**
     * Blocking loop. Must be called from a dedicated background thread.
     * Returns when {@link #stop()} is called and the thread is interrupted.
     */
    public void run() {
        running = true;
        Log.i(TAG, "run() start — listening for KFD");
        status("Waiting for KFD…");
        while (running) {
            try {
                waitForKeySignature();
                if (!running) return;
                Log.i(TAG, "Key signature (0x00) received; waiting for READY_REQ");

                byte req = adapter.getByte(TIMEOUT_STD);
                Log.i(TAG, "RX opcode 0x" + hex1(req));
                if (!running) return;
                if ((req & 0xFF) != (OPCODE_READY_REQ & 0xFF)) {
                    Log.w(TAG, "Expected READY_REQ 0xC0, got 0x" + hex1(req) + " — resetting");
                    continue;
                }

                // Announce ourselves as an MR device
                List<Byte> rsp = new ArrayList<>();
                rsp.add(OPCODE_READY_GENERAL_MODE_MR);
                Log.i(TAG, "TX READY_GENERAL_MODE_MR (0xD0)");
                adapter.sendData(rsp);
                if (listener != null) listener.onSessionStarted();

                runSession();

                Log.i(TAG, "runSession() returned — restart listen loop");
                if (listener != null) listener.onSessionEnded();

            } catch (InterruptedException e) {
                Log.i(TAG, "Interrupted — exiting run()");
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                if (!running) return;
                Log.w(TAG, "run() IOException: " + e.getMessage() + " — resetting");
                // Only surface real errors to the user — not the routine per-cycle
                // timeouts that the KVL's polling produces. Bare "Timeout" strings
                // from the adapter are noise; anything else is worth reporting.
                String msg = e.getMessage();
                if (msg != null && !msg.toLowerCase().contains("timeout")) {
                    status("Adapter error — reconnecting…");
                }
            }
        }
        Log.i(TAG, "run() exit (running=false)");
    }

    // ── Session loop ──────────────────────────────────────────────────────────

    private void runSession() throws IOException, InterruptedException {
        while (running) {
            // Wait indefinitely for the next opcode. The KVL can go quiet for many
            // seconds between sessions; a 5s timeout here surfaced as an alarming
            // "Timeout waiting for broadcast byte" every cycle. The Cancel button
            // in the MR Emulator dialog interrupts the thread and unblocks us.
            byte opcode = adapter.getByte(0);
            Log.i(TAG, "runSession RX opcode 0x" + hex1(opcode));
            if (!running) return;

            if ((opcode & 0xFF) == (OPCODE_KMM & 0xFF)) {
                Log.i(TAG, "  → KMM frame");
                handleKmmFrame();
            } else if ((opcode & 0xFF) == (OPCODE_TRANSFER_DONE & 0xFF)) {
                Log.i(TAG, "  → TRANSFER_DONE; echoing");
                // Echo TRANSFER_DONE. Historically we then required a DISCONNECT
                // and returned from the session — but the Motorola KVL sometimes
                // sends TRANSFER_DONE mid-flow without a following DISCONNECT and
                // continues with more KMM commands. Keep the session running so
                // the user's Cancel button is the only thing that stops us.
                List<Byte> td = new ArrayList<>();
                td.add(OPCODE_TRANSFER_DONE);
                adapter.sendData(td);
            } else if ((opcode & 0xFF) == (OPCODE_DISCONNECT & 0xFF)) {
                Log.i(TAG, "  → DISCONNECT; ACKing but continuing to listen (no return)");
                List<Byte> dack = new ArrayList<>();
                dack.add(OPCODE_DISCONNECT_ACK);
                adapter.sendData(dack);
                // Deliberately no return — Motorola KVL testing: keep listening
                // until the user hits the Cancel button in the MR Emulator dialog.
            } else if ((opcode & 0xFF) == 0x00) {
                // Key signature for a new session — KVL's typical pattern is a
                // connectivity probe (READY_REQ → TRANSFER_DONE → DISCONNECT) followed
                // by another 0x00 that starts the real keyload session. Handle the
                // READY_REQ handshake inline and stay in the session loop.
                Log.i(TAG, "  → key signature (0x00) mid-session — restarting handshake");
                handleReadyRequestHandshake();
            } else {
                Log.w(TAG, "  → unexpected opcode 0x" + hex1(opcode) + " during session; resetting");
                return;
            }
        }
    }

    /**
     * Read the next byte, expect READY_REQ (0xC0), respond with READY_GENERAL_MODE_MR (0xD0).
     * Used both for the initial handshake and for a KVL-initiated re-handshake within a session.
     */
    private void handleReadyRequestHandshake() throws IOException, InterruptedException {
        byte req = adapter.getByte(TIMEOUT_STD);
        Log.i(TAG, "handshake RX opcode 0x" + hex1(req));
        if ((req & 0xFF) != (OPCODE_READY_REQ & 0xFF)) {
            Log.w(TAG, "handshake expected READY_REQ 0xC0, got 0x" + hex1(req));
            return;
        }
        List<Byte> rsp = new ArrayList<>();
        rsp.add(OPCODE_READY_GENERAL_MODE_MR);
        Log.i(TAG, "handshake TX READY_GENERAL_MODE_MR (0xD0)");
        adapter.sendData(rsp);
        if (listener != null) listener.onSessionStarted();
    }

    // ── KMM handling ──────────────────────────────────────────────────────────

    private void handleKmmFrame() throws IOException, InterruptedException {
        byte[] kmmBytes = receiveKmmFrame();
        int messageId = kmmBytes[0] & 0xFF;
        Log.i(TAG, "KMM RX msgId=0x" + String.format("%02X", messageId)
                + " len=" + kmmBytes.length + " body=" + bytesToHex(kmmBytes));

        if (messageId == MessageId.InventoryCommand) {
            // KMM body starts at offset 10 (after header+RSI fields in the frame)
            int invType = kmmBytes.length > 10 ? (kmmBytes[10] & 0xFF) : -1;
            if (invType == INV_TYPE_LIST_ACTIVE_KSET_IDS) {
                // Return empty keyset list — matches factory Motorola behavior.
                InventoryResponseListActiveKsetIds resp = new InventoryResponseListActiveKsetIds();
                sendKmm(new KmmFrame(resp).toBytes());
            } else if (invType == INV_TYPE_LIST_RSI_ITEMS) {
                // KVL5000 sends this before it will keyload. A NACK here makes it give
                // up. Reply with a single factory-default RSI item (RSI=1, MsgNum=0)
                // to match Motorola SU behavior.
                InventoryResponseListRsiItems resp = new InventoryResponseListRsiItems();
                resp.rsiItems.add(new InventoryResponseListRsiItems.RsiItem(0x000001, 0x0000));
                sendKmm(new KmmFrame(resp).toBytes());
            } else {
                sendNack(MessageId.InventoryCommand, 0x03);
                Log.w(TAG, "Unknown inventory type 0x" + String.format("%02X", invType) + " — NACK sent");
            }

        } else if (messageId == MessageId.ModifyKeyCommand) {
            KmmFrame frame = new KmmFrame(false, kmmBytes);
            ModifyKeyCommand cmd = (ModifyKeyCommand) frame.kmmBody;
            Log.i(TAG, "Key load: " + cmd.keyItems.size() + " key(s), keyset " + cmd.keysetId
                    + ", alg 0x" + String.format("%02X", cmd.algorithmId));

            for (KeyItem ki : cmd.keyItems) {
                Log.i(TAG, "onKeyReceived keyset=" + cmd.keysetId
                        + " alg=0x" + String.format("%02X", cmd.algorithmId)
                        + " keyId=" + ki.keyId + " sln=" + ki.sln
                        + " erase=" + ki.erase + " keyLen=" + (ki.key == null ? 0 : ki.key.length));
                if (listener != null) {
                    listener.onKeyReceived(cmd.keysetId, cmd.algorithmId, ki.keyId, ki.sln, ki.erase, ki.key);
                }
            }
            Log.i(TAG, "TX RekeyAcknowledgment for " + cmd.keyItems.size() + " key(s)");

            RekeyAcknowledgment ack = new RekeyAcknowledgment();
            ack.messageIdAcknowledged = MessageId.ModifyKeyCommand;
            ack.numberOfItems = cmd.keyItems.size();
            for (KeyItem ki : cmd.keyItems) {
                KeyStatus ks = new KeyStatus();
                ks.algorithmId = cmd.algorithmId;
                ks.keyId = ki.keyId;
                ks.status = 0x00; // success
                ack.keys.add(ks);
            }
            sendKmm(new KmmFrame(ack).toBytes());

        } else {
            sendNack(messageId, 0x03); // InvalidMessageId
            Log.w(TAG, "Unknown message ID 0x" + String.format("%02X", messageId) + " — NACK sent");
        }
    }

    // ── Frame I/O (matches ThreeWireProtocol exactly) ─────────────────────────

    /** Read a transport-framed KMM from the adapter byte by byte. Returns raw KMM frame bytes. */
    private byte[] receiveKmmFrame() throws IOException, InterruptedException {
        byte hi = adapter.getByte(TIMEOUT_STD);
        byte lo = adapter.getByte(TIMEOUT_STD);
        int length = ((hi & 0xFF) << 8) | (lo & 0xFF);

        List<Byte> toCrc = new ArrayList<>();

        byte ctrl = adapter.getByte(TIMEOUT_STD);
        toCrc.add(ctrl);

        byte rsi0 = adapter.getByte(TIMEOUT_STD);
        byte rsi1 = adapter.getByte(TIMEOUT_STD);
        byte rsi2 = adapter.getByte(TIMEOUT_STD);
        toCrc.add(rsi0); toCrc.add(rsi1); toCrc.add(rsi2);

        int bodyLength = length - 6; // ctrl(1) + destRSI(3) + CRC(2) = 6
        if (bodyLength < 0) throw new IOException("Invalid KMM frame length: " + length);

        List<Byte> kmm = new ArrayList<>();
        for (int i = 0; i < bodyLength; i++) {
            byte b = adapter.getByte(TIMEOUT_STD);
            kmm.add(b);
        }
        toCrc.addAll(kmm);

        byte[] toCrcArr = new byte[toCrc.size()];
        for (int i = 0; i < toCrc.size(); i++) toCrcArr[i] = toCrc.get(i);
        byte[] expectedCrc = CRC16.calculate(toCrcArr);

        byte crcLo = adapter.getByte(TIMEOUT_STD);
        byte crcHi = adapter.getByte(TIMEOUT_STD);
        if (expectedCrc[0] != crcLo || expectedCrc[1] != crcHi) {
            throw new IOException(String.format("CRC mismatch: expected %02X %02X, got %02X %02X",
                    expectedCrc[0] & 0xFF, expectedCrc[1] & 0xFF, crcLo & 0xFF, crcHi & 0xFF));
        }

        byte[] result = new byte[kmm.size()];
        for (int i = 0; i < kmm.size(); i++) result[i] = kmm.get(i);
        return result;
    }

    private void sendKmm(byte[] kmmBytes) throws IOException, InterruptedException {
        adapter.sendData(buildKmmFrame(kmmBytes));
    }

    private void sendNack(int messageId, int status) throws IOException, InterruptedException {
        NegativeAcknowledgment nack = new NegativeAcknowledgment();
        nack.acknowledgedMessageId = messageId;
        nack.status = status;
        sendKmm(new KmmFrame(nack).toBytes());
    }

    /** Build the transport wrapper around a KMM frame (matches ThreeWireProtocol.buildKmmFrame). */
    private static List<Byte> buildKmmFrame(byte[] kmm) {
        List<Byte> body = new ArrayList<>();
        body.add((byte) 0x00); // control
        body.add((byte) 0xFF); // dest RSI hi
        body.add((byte) 0xFF); // dest RSI mid
        body.add((byte) 0xFF); // dest RSI lo
        for (byte b : kmm) body.add(b);

        byte[] rawBody = new byte[body.size()];
        for (int i = 0; i < body.size(); i++) rawBody[i] = body.get(i);
        byte[] crc = CRC16.calculate(rawBody);

        int length = body.size() + 2;

        List<Byte> frame = new ArrayList<>();
        frame.add((byte) 0xC2); // OPCODE_KMM
        frame.add((byte) ((length >> 8) & 0xFF));
        frame.add((byte) (length & 0xFF));
        frame.addAll(body);
        frame.add(crc[0]);
        frame.add(crc[1]);
        return frame;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Wait indefinitely for the 0x00 key-signature byte from the KFD. */
    private void waitForKeySignature() throws IOException, InterruptedException {
        byte b;
        do {
            b = adapter.getByte(0); // 0 → Integer.MAX_VALUE timeout (effectively indefinite)
            if (!running) return;
        } while ((b & 0xFF) != 0x00);
    }

    private void status(String msg) {
        if (listener != null) listener.onStatus(msg);
    }

    private static String hex1(byte b) {
        return String.format("%02X", b & 0xFF);
    }

    private static String bytesToHex(byte[] b) {
        if (b == null || b.length == 0) return "(empty)";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte v : b) sb.append(String.format("%02X ", v));
        return sb.toString().trim();
    }
}
