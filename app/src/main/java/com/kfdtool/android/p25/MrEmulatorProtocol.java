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
package com.kfdtool.android.p25;

import com.kfdtool.android.p25.kmm.InventoryResponseListActiveKsetIds;
import com.kfdtool.android.p25.kmm.KeyItem;
import com.kfdtool.android.p25.kmm.KeyStatus;
import com.kfdtool.android.p25.kmm.KmmFrame;
import com.kfdtool.android.p25.kmm.MessageId;
import com.kfdtool.android.p25.kmm.ModifyKeyCommand;
import com.kfdtool.android.p25.kmm.NegativeAcknowledgment;
import com.kfdtool.android.p25.kmm.RekeyAcknowledgment;
import com.kfdtool.android.protocol.AdapterProtocol;

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

    private static final int TIMEOUT_STD = 5000; // ms

    private static final byte OPCODE_READY_REQ             = (byte) 0xC0;
    private static final byte OPCODE_READY_GENERAL_MODE_MR = (byte) 0xD0;
    private static final byte OPCODE_TRANSFER_DONE         = (byte) 0xC1;
    private static final byte OPCODE_KMM                   = (byte) 0xC2;
    private static final byte OPCODE_DISCONNECT            = (byte) 0x92;
    private static final byte OPCODE_DISCONNECT_ACK        = (byte) 0x90;

    private static final int INV_TYPE_LIST_ACTIVE_KSET_IDS = 0x02;

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
        status("Listening for KFD...");
        while (running) {
            try {
                waitForKeySignature();
                if (!running) return;

                byte req = adapter.getByte(TIMEOUT_STD);
                if (!running) return;
                if ((req & 0xFF) != (OPCODE_READY_REQ & 0xFF)) {
                    status("Unexpected byte 0x" + hex1(req) + " (expected READY_REQ 0xC0), resetting...");
                    continue;
                }

                // Announce ourselves as an MR device
                List<Byte> rsp = new ArrayList<>();
                rsp.add(OPCODE_READY_GENERAL_MODE_MR);
                adapter.sendData(rsp);
                status("KFD connected.");
                if (listener != null) listener.onSessionStarted();

                runSession();

                status("Session ended. Listening for KFD...");
                if (listener != null) listener.onSessionEnded();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                if (!running) return;
                status("Error: " + e.getMessage() + " — resetting...");
            }
        }
    }

    // ── Session loop ──────────────────────────────────────────────────────────

    private void runSession() throws IOException, InterruptedException {
        while (running) {
            byte opcode = adapter.getByte(TIMEOUT_STD);
            if (!running) return;

            if ((opcode & 0xFF) == (OPCODE_KMM & 0xFF)) {
                handleKmmFrame();
            } else if ((opcode & 0xFF) == (OPCODE_TRANSFER_DONE & 0xFF)) {
                // Echo TRANSFER_DONE, then wait for DISCONNECT
                List<Byte> td = new ArrayList<>();
                td.add(OPCODE_TRANSFER_DONE);
                adapter.sendData(td);

                try {
                    byte disc = adapter.getByte(TIMEOUT_STD);
                    if ((disc & 0xFF) == (OPCODE_DISCONNECT & 0xFF)) {
                        List<Byte> dack = new ArrayList<>();
                        dack.add(OPCODE_DISCONNECT_ACK);
                        adapter.sendData(dack);
                    }
                } catch (IOException ignored) {
                    // Timeout waiting for DISCONNECT — session ends anyway
                }
                return;
            } else {
                status("Unexpected opcode 0x" + hex1(opcode) + " during session, resetting...");
                return;
            }
        }
    }

    // ── KMM handling ──────────────────────────────────────────────────────────

    private void handleKmmFrame() throws IOException, InterruptedException {
        byte[] kmmBytes = receiveKmmFrame();
        int messageId = kmmBytes[0] & 0xFF;

        if (messageId == MessageId.InventoryCommand) {
            // KMM body starts at offset 10 (after header+RSI fields in the frame)
            int invType = kmmBytes.length > 10 ? (kmmBytes[10] & 0xFF) : -1;
            if (invType == INV_TYPE_LIST_ACTIVE_KSET_IDS) {
                // Return empty keyset list — matches factory Motorola behavior
                InventoryResponseListActiveKsetIds resp = new InventoryResponseListActiveKsetIds();
                sendKmm(new KmmFrame(resp).toBytes());
                status("Inventory query — responded with empty keyset list.");
            } else {
                sendNack(MessageId.InventoryCommand, 0x03);
                status("Unknown inventory type 0x" + String.format("%02X", invType) + " — NACK sent.");
            }

        } else if (messageId == MessageId.ModifyKeyCommand) {
            KmmFrame frame = new KmmFrame(false, kmmBytes);
            ModifyKeyCommand cmd = (ModifyKeyCommand) frame.kmmBody;

            status("Key load: " + cmd.keyItems.size() + " key(s), keyset " + cmd.keysetId
                    + ", alg 0x" + String.format("%02X", cmd.algorithmId));

            for (KeyItem ki : cmd.keyItems) {
                if (listener != null) {
                    listener.onKeyReceived(cmd.keysetId, cmd.algorithmId, ki.keyId, ki.sln, ki.erase, ki.key);
                }
            }

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
            status("Unknown message ID 0x" + String.format("%02X", messageId) + " — NACK sent.");
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
}
