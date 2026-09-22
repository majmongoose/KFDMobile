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

import com.sentinelcomms.kfdmobile.protocol.AdapterProtocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * P25 Three-Wire Interface (TWI) protocol layer.
 *
 * Sequence for a KFD acting as KVL (key loader) to an MR (mobile radio):
 *   1. sendKeySignature (or sendKeySignatureAndReadyReq)
 *   2. initSession → returns DeviceType (Mr or Kvl)
 *   3. performKmmTransfer (one or more times)
 *   4. endSession
 *
 * Mirrors ThreeWireProtocol.cs from the .NET source.
 */
public class ThreeWireProtocol implements DeviceProtocol {

    private static final int TIMEOUT_STD  = 5000; // 5 seconds

    private static final byte OPCODE_READY_REQ              = (byte) 0xC0;
    private static final byte OPCODE_READY_GENERAL_MODE_MR  = (byte) 0xD0;
    private static final byte OPCODE_READY_GENERAL_MODE_KVL = (byte) 0xD1;
    private static final byte OPCODE_TRANSFER_DONE          = (byte) 0xC1;
    private static final byte OPCODE_KMM                    = (byte) 0xC2;
    private static final byte OPCODE_KMM_MFID               = (byte) 0xC3;
    private static final byte OPCODE_DISCONNECT_ACK         = (byte) 0x90;
    private static final byte OPCODE_DISCONNECT             = (byte) 0x92;

    private final AdapterProtocol adapter;
    private Consumer<String> debugListener;
    private boolean lastResponseHadPreamble = false;

    public ThreeWireProtocol(AdapterProtocol adapter) {
        this.adapter = adapter;
    }

    // ── DeviceProtocol ────────────────────────────────────────────────────────

    @Override
    public void setDebugListener(Consumer<String> listener) {
        this.debugListener = listener;
    }

    @Override
    public void clearQueues() {
        adapter.clearQueues();
    }

    @Override
    public boolean lastResponseHadPreamble() {
        return lastResponseHadPreamble;
    }

    @Override
    public void sendKeySignature() throws IOException, InterruptedException {
        if (adapter.featureAvailableSendKeySignatureAndReadyReq()) {
            dbg("TX: key signature + ready request");
            adapter.sendKeySignatureAndReadyReq();
        } else {
            dbg("TX: key signature");
            adapter.sendKeySignature();
        }
    }

    @Override
    public DeviceType initSession() throws IOException, InterruptedException {
        if (!adapter.featureAvailableSendKeySignatureAndReadyReq()) {
            dbg("TX: READY_REQ (0xC0)");
            List<Byte> cmd = new ArrayList<>();
            cmd.add(OPCODE_READY_REQ);
            adapter.sendData(cmd);
        }

        byte rsp = adapter.getByte(TIMEOUT_STD);
        if (rsp == OPCODE_READY_GENERAL_MODE_MR) {
            dbg("RX: 0xD0 - READY_GENERAL_MODE (MR)");
            return DeviceType.Mr;
        } else if (rsp == OPCODE_READY_GENERAL_MODE_KVL) {
            dbg("RX: 0xD1 - READY_GENERAL_MODE (KVL)");
            return DeviceType.Kvl;
        } else {
            throw new IOException("Unexpected opcode in initSession: 0x" + String.format("%02X", rsp));
        }
    }

    @Override
    public void endSession() throws IOException, InterruptedException {
        dbg("TX: TRANSFER_DONE (0xC1)");
        List<Byte> cmd1 = new ArrayList<>();
        cmd1.add(OPCODE_TRANSFER_DONE);
        adapter.sendData(cmd1);

        byte rsp1 = adapter.getByte(TIMEOUT_STD);
        dbg(String.format("RX: 0x%02X%s", rsp1 & 0xFF,
                rsp1 == OPCODE_TRANSFER_DONE ? " - TRANSFER_DONE" : " - unexpected"));
        if (rsp1 != OPCODE_TRANSFER_DONE)
            throw new IOException("Expected TRANSFER_DONE, got 0x" + String.format("%02X", rsp1));

        dbg("TX: DISCONNECT (0x92)");
        List<Byte> cmd2 = new ArrayList<>();
        cmd2.add(OPCODE_DISCONNECT);
        adapter.sendData(cmd2);

        byte rsp2 = adapter.getByte(TIMEOUT_STD);
        dbg(String.format("RX: 0x%02X%s", rsp2 & 0xFF,
                rsp2 == OPCODE_DISCONNECT_ACK ? " - DISCONNECT_ACK" : " - unexpected"));
        if (rsp2 != OPCODE_DISCONNECT_ACK)
            throw new IOException("Expected DISCONNECT_ACK, got 0x" + String.format("%02X", rsp2));
    }

    /**
     * Send a KMM frame to the radio and receive the response KMM frame.
     * @param toRadio serialized KMM body bytes
     * @return raw KMM body bytes from the radio response
     */
    @Override
    public byte[] performKmmTransfer(byte[] toRadio) throws IOException, InterruptedException {
        if (toRadio.length > 512) throw new IOException("KMM exceeds max size");

        dbg("TX KMM (" + toRadio.length + " bytes): " + toHex(toRadio));
        List<Byte> txFrame = buildKmmFrame(toRadio);
        adapter.sendData(txFrame);

        byte rx = adapter.getByte(TIMEOUT_STD);
        if (rx == OPCODE_KMM) {
            dbg("RX: 0xC2 - KMM");
            lastResponseHadPreamble = false;
        } else if (rx == OPCODE_KMM_MFID) {
            dbg("RX: 0xC3 - KMM (Motorola MFID, with preamble)");
            lastResponseHadPreamble = true;
        } else {
            dbg(String.format("RX: 0x%02X - unexpected", rx & 0xFF));
            throw new IOException("Expected KMM opcode 0xC2/0xC3, got 0x" + String.format("%02X", rx));
        }

        byte[] rxKmm = receiveKmmFrame();
        dbg("RX KMM (" + rxKmm.length + " bytes): " + toHex(rxKmm));
        return rxKmm;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void dbg(String msg) {
        if (debugListener != null) debugListener.accept(msg);
    }

    private static String toHex(byte[] data) {
        if (data == null || data.length == 0) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    private List<Byte> buildKmmFrame(byte[] kmm) {
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
        frame.add(OPCODE_KMM);
        frame.add((byte)((length >> 8) & 0xFF));
        frame.add((byte)(length & 0xFF));
        frame.addAll(body);
        frame.add(crc[0]);
        frame.add(crc[1]);
        return frame;
    }

    private byte[] receiveKmmFrame() throws IOException, InterruptedException {
        dbg("Waiting for length hi...");
        byte hi = adapter.getByte(TIMEOUT_STD);
        dbg(String.format("RX length hi: 0x%02X", hi & 0xFF));
        byte lo = adapter.getByte(TIMEOUT_STD);
        dbg(String.format("RX length lo: 0x%02X", lo & 0xFF));
        int length = ((hi & 0xFF) << 8) | (lo & 0xFF);
        dbg("Frame body length field: " + length);

        List<Byte> toCrc = new ArrayList<>();

        byte ctrl = adapter.getByte(TIMEOUT_STD);
        dbg(String.format("RX ctrl: 0x%02X", ctrl & 0xFF));
        toCrc.add(ctrl);

        byte rsi0 = adapter.getByte(TIMEOUT_STD);
        byte rsi1 = adapter.getByte(TIMEOUT_STD);
        byte rsi2 = adapter.getByte(TIMEOUT_STD);
        dbg(String.format("RX dest RSI: %02X %02X %02X", rsi0 & 0xFF, rsi1 & 0xFF, rsi2 & 0xFF));
        toCrc.add(rsi0); toCrc.add(rsi1); toCrc.add(rsi2);

        int bodyLength = length - 6;
        dbg("KMM body length: " + bodyLength);
        List<Byte> kmm = new ArrayList<>();
        for (int i = 0; i < bodyLength; i++) {
            byte b = adapter.getByte(TIMEOUT_STD);
            dbg(String.format("  KMM[%d/%d]: 0x%02X", i + 1, bodyLength, b & 0xFF));
            kmm.add(b);
        }
        toCrc.addAll(kmm);

        byte[] toCrcArr = new byte[toCrc.size()];
        for (int i = 0; i < toCrc.size(); i++) toCrcArr[i] = toCrc.get(i);
        byte[] expectedCrc = CRC16.calculate(toCrcArr);

        byte crcLo = adapter.getByte(TIMEOUT_STD);
        byte crcHi = adapter.getByte(TIMEOUT_STD);

        dbg(String.format("CRC: expected %02X %02X  received %02X %02X",
                expectedCrc[0] & 0xFF, expectedCrc[1] & 0xFF, crcLo & 0xFF, crcHi & 0xFF));
        if (expectedCrc[0] != crcLo || expectedCrc[1] != crcHi) {
            throw new IOException(String.format("CRC mismatch: expected %02X %02X, got %02X %02X",
                    expectedCrc[0] & 0xFF, expectedCrc[1] & 0xFF, crcLo & 0xFF, crcHi & 0xFF));
        }

        byte[] result = new byte[kmm.size()];
        for (int i = 0; i < kmm.size(); i++) result[i] = kmm.get(i);
        return result;
    }
}
