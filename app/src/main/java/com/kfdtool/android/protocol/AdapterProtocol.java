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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Higher-level adapter command/response protocol that sits on top of KfdFrameProtocol.
 *
 * Mirrors AdapterProtocol.cs from the .NET source.
 */
public class AdapterProtocol {

    // Command opcodes
    private static final byte CMD_READ                      = 0x11;
    private static final byte CMD_WRITE_INFO                = 0x12;
    private static final byte CMD_RESET                     = 0x14;
    private static final byte CMD_SELF_TEST                 = 0x15;
    private static final byte CMD_SEND_KEY_SIG              = 0x16;
    private static final byte CMD_SEND_BYTE                 = 0x17;
    private static final byte CMD_SEND_BYTES                = 0x18;
    private static final byte CMD_SEND_KEY_SIG_AND_READY_REQ= 0x19;

    // Response opcodes
    private static final byte RSP_READ                      = 0x21;
    private static final byte RSP_WRITE_INFO                = 0x22;
    private static final byte RSP_RESET                     = 0x24;
    private static final byte RSP_SELF_TEST                 = 0x25;
    private static final byte RSP_SEND_KEY_SIG              = 0x26;
    private static final byte RSP_SEND_BYTE                 = 0x27;
    private static final byte RSP_SEND_BYTES                = 0x28;
    private static final byte RSP_SEND_KEY_SIG_AND_READY_REQ= 0x29;

    // Broadcast opcode
    static final byte BCST_RECEIVE_BYTE                    = 0x31;

    // Read sub-opcodes
    private static final byte READ_AP_VER   = 0x01;
    private static final byte READ_FW_VER   = 0x02;
    private static final byte READ_UNIQUE_ID= 0x03;
    private static final byte READ_MODEL_ID = 0x04;
    private static final byte READ_HW_REV  = 0x05;
    private static final byte READ_SER_NUM = 0x06;

    // Write sub-opcodes
    private static final byte WRITE_DEFAULT_TRANSFER_SPEED = 0x03;
    private static final byte WRITE_TX_TRANSFER_SPEED      = 0x04;
    private static final byte WRITE_RX_TRANSFER_SPEED      = 0x05;

    private static final int TIMEOUT_MS = 2000;
    private static final int DATA_BYTES_PER_CMD = 250;

    private final KfdFrameProtocol lower;

    // Protocol version detected on open
    private int versionMajor;
    private int versionMinor;
    private int versionPatch;

    public AdapterProtocol(KfdFrameProtocol lower) {
        this.lower = lower;
    }

    /** Call once after the serial port is open. Reads protocol version and sets transfer speed. */
    public void initialize() throws IOException, InterruptedException {
        lower.clear();
        readAdapterProtocolVersion();
        if (featureAvailableSetTransferSpeed()) {
            setDefaultTransferSpeed();
        }
    }

    public boolean featureAvailableSendBytes() {
        return compareVersion(2, 1, 0) >= 0;
    }

    public boolean featureAvailableSetTransferSpeed() {
        return compareVersion(2, 1, 0) >= 0;
    }

    public boolean featureAvailableSendKeySignatureAndReadyReq() {
        return compareVersion(2, 2, 0) >= 0;
    }

    private int compareVersion(int maj, int min, int pat) {
        if (versionMajor != maj) return versionMajor - maj;
        if (versionMinor != min) return versionMinor - min;
        return versionPatch - pat;
    }

    // ---------- READ commands ----------

    public int[] readAdapterProtocolVersion() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_READ);
        cmd.add(READ_AP_VER);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 5) throw new IOException("AP version: invalid response length " + rsp.size());
        if (rsp.get(0) != RSP_READ) throw new IOException("AP version: invalid opcode");
        if (rsp.get(1) != READ_AP_VER) throw new IOException("AP version: invalid sub-opcode");
        versionMajor = rsp.get(2) & 0xFF;
        versionMinor = rsp.get(3) & 0xFF;
        versionPatch = rsp.get(4) & 0xFF;
        return new int[]{ versionMajor, versionMinor, versionPatch };
    }

    public int[] readFirmwareVersion() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_READ);
        cmd.add(READ_FW_VER);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 5) throw new IOException("FW version: invalid response length");
        if (rsp.get(0) != RSP_READ || rsp.get(1) != READ_FW_VER) throw new IOException("FW version: invalid opcodes");
        return new int[]{ rsp.get(2) & 0xFF, rsp.get(3) & 0xFF, rsp.get(4) & 0xFF };
    }

    public byte[] readUniqueId() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_READ);
        cmd.add(READ_UNIQUE_ID);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() < 3) throw new IOException("Unique ID: response too short");
        if (rsp.get(0) != RSP_READ || rsp.get(1) != READ_UNIQUE_ID) throw new IOException("Unique ID: invalid opcodes");
        int len = rsp.get(2) & 0xFF;
        if (len == 0) return new byte[0];
        if (len != rsp.size() - 3) throw new IOException("Unique ID: length mismatch");
        byte[] id = new byte[len];
        for (int i = 0; i < len; i++) id[i] = rsp.get(3 + i);
        return id;
    }

    public int readModelId() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_READ);
        cmd.add(READ_MODEL_ID);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 3) throw new IOException("Model ID: invalid response length");
        if (rsp.get(0) != RSP_READ || rsp.get(1) != READ_MODEL_ID) throw new IOException("Model ID: invalid opcodes");
        return rsp.get(2) & 0xFF;
    }

    public int[] readHardwareRevision() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_READ);
        cmd.add(READ_HW_REV);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 4) throw new IOException("HW revision: invalid response length");
        if (rsp.get(0) != RSP_READ || rsp.get(1) != READ_HW_REV) throw new IOException("HW revision: invalid opcodes");
        return new int[]{ rsp.get(2) & 0xFF, rsp.get(3) & 0xFF };
    }

    public byte[] readSerialNumber() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_READ);
        cmd.add(READ_SER_NUM);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() < 3) throw new IOException("Serial number: response too short");
        if (rsp.get(0) != RSP_READ || rsp.get(1) != READ_SER_NUM) throw new IOException("Serial number: invalid opcodes");
        int len = rsp.get(2) & 0xFF;
        if (len == 0) return new byte[0];
        if (len != rsp.size() - 3) throw new IOException("Serial number: length mismatch");
        byte[] sn = new byte[len];
        for (int i = 0; i < len; i++) sn[i] = rsp.get(3 + i);
        return sn;
    }

    // ---------- WRITE / control commands ----------

    private void setDefaultTransferSpeed() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_WRITE_INFO);
        cmd.add(WRITE_DEFAULT_TRANSFER_SPEED);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 1 || rsp.get(0) != RSP_WRITE_INFO) throw new IOException("Set default speed: unexpected response");
    }

    public void reset() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_RESET);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 1 || rsp.get(0) != RSP_RESET) throw new IOException("Reset: unexpected response");
    }

    public int selfTest() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_SELF_TEST);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 2 || rsp.get(0) != RSP_SELF_TEST) throw new IOException("Self test: unexpected response");
        return rsp.get(1) & 0xFF;
    }

    // ---------- P25 three-wire commands ----------

    public void sendKeySignature() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_SEND_KEY_SIG);
        cmd.add((byte) 0x00);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 1 || rsp.get(0) != RSP_SEND_KEY_SIG) throw new IOException("Key sig: unexpected response");
    }

    public void sendKeySignatureAndReadyReq() throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_SEND_KEY_SIG_AND_READY_REQ);
        cmd.add((byte) 0x00);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 1 || rsp.get(0) != RSP_SEND_KEY_SIG_AND_READY_REQ)
            throw new IOException("Key sig+ready req: unexpected response");
    }

    public void sendByte(byte data) throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_SEND_BYTE);
        cmd.add((byte) 0x00);
        cmd.add(data);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 1 || rsp.get(0) != RSP_SEND_BYTE) throw new IOException("Send byte: unexpected response");
    }

    private void sendBytes(List<Byte> data) throws IOException, InterruptedException {
        List<Byte> cmd = new ArrayList<>();
        cmd.add(CMD_SEND_BYTES);
        cmd.add((byte) 0x00);
        cmd.add((byte) ((data.size() >> 8) & 0xFF));
        cmd.add((byte) (data.size() & 0xFF));
        cmd.addAll(data);
        lower.send(cmd);
        List<Byte> rsp = lower.read(TIMEOUT_MS);
        if (rsp.size() != 1 || rsp.get(0) != RSP_SEND_BYTES) throw new IOException("Send bytes: unexpected response");
    }

    /** Send one or more data bytes to the radio, chunking if necessary. */
    public void sendData(List<Byte> data) throws IOException, InterruptedException {
        if (data.isEmpty()) return;
        if (featureAvailableSendBytes()) {
            if (data.size() <= DATA_BYTES_PER_CMD) {
                sendBytes(data);
            } else {
                for (int offset = 0; offset < data.size(); offset += DATA_BYTES_PER_CMD) {
                    int end = Math.min(offset + DATA_BYTES_PER_CMD, data.size());
                    sendBytes(new ArrayList<>(data.subList(offset, end)));
                }
            }
        } else {
            for (byte b : data) sendByte(b);
        }
    }

    /**
     * Wait for a broadcast byte from the radio (e.g. during P25 session).
     * @param timeoutMs ms to wait; 0 = indefinite
     */
    public byte getByte(int timeoutMs) throws IOException, InterruptedException {
        List<Byte> rsp = lower.readBroadcast(timeoutMs == 0 ? Integer.MAX_VALUE : timeoutMs);
        if (rsp.size() != 3) throw new IOException("GetByte: invalid broadcast length " + rsp.size());
        if (rsp.get(0) != BCST_RECEIVE_BYTE) throw new IOException("GetByte: unexpected opcode 0x" + String.format("%02X", rsp.get(0)));
        return rsp.get(2);
    }

    /** Flush both receive queues — call before starting a new session. */
    public void clearQueues() {
        lower.clear();
    }

    public String getVersionString() {
        return versionMajor + "." + versionMinor + "." + versionPatch;
    }
}
