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

import com.sentinelcomms.kfdmobile.p25.kmm.KmmBody;
import com.sentinelcomms.kfdmobile.p25.kmm.KmmFrame;
import com.sentinelcomms.kfdmobile.p25.kmm.Mfid90SessionControlVer1;
import com.sentinelcomms.kfdmobile.p25.kmm.SessionControl;
import com.sentinelcomms.kfdmobile.protocol.UdpTransport;

import java.util.Locale;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * P25 Data-Link-Independent (DLI) protocol layer.
 *
 * KMM frames are exchanged over UDP with a 14-byte preamble on every message.
 * Supports the standard (MFID=0x00) and Motorola (MFID=0x90) session variants.
 *
 * Mirrors DataLinkIndependentProtocol.cs from the .NET source.
 */
public class DataLinkIndependentProtocol implements DeviceProtocol {

    /** Standard (TIA) MFID byte. */
    static final int MFID_STANDARD = 0x00;

    /** Motorola proprietary MFID byte. */
    static final int MFID_MOTOROLA = 0x90;

    private final UdpTransport udp;
    private final boolean      motVariant;
    private final int          mfid;

    private Consumer<String> debugListener;

    public DataLinkIndependentProtocol(UdpTransport udp, boolean motVariant) {
        this.udp        = udp;
        this.motVariant = motVariant;
        this.mfid       = motVariant ? MFID_MOTOROLA : MFID_STANDARD;
    }

    @Override
    public void setDebugListener(Consumer<String> listener) {
        this.debugListener = listener;
    }

    private void dbg(String msg) {
        if (debugListener != null) debugListener.accept(msg);
    }

    private static String toHex(byte[] data) {
        if (data == null || data.length == 0) return "(empty)";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format(Locale.ROOT, "%02X ", b));
        return sb.toString().trim();
    }

    // ── DeviceProtocol ────────────────────────────────────────────────────────

    /** No-op: DLI does not use a key signature. */
    @Override
    public void sendKeySignature() {
        dbg("DLI: sendKeySignature (no-op)");
    }

    @Override
    public DeviceType initSession() throws IOException {
        if (motVariant) {
            mfid90SendConnect();
            mfid90SendBeginSession();
        } else {
            sendReadyRequest();
        }
        return DeviceType.Mr; // DLI always connects to an MR
    }

    @Override
    public void endSession() throws IOException {
        if (motVariant) {
            mfid90SendTransferDone();
            mfid90SendEndSession();
            mfid90SendDisconnect();
        } else {
            sendTransferDone();
            sendEndSession();
            sendDisconnect();
        }
    }

    /** UDP send/receive. The caller has already wrapped toRadio with the preamble. */
    @Override
    public byte[] performKmmTransfer(byte[] toRadio) throws IOException {
        dbg("DLI TX (" + toRadio.length + " bytes): " + toHex(toRadio));
        byte[] fromRadio = udp.txRx(toRadio);
        dbg("DLI RX (" + fromRadio.length + " bytes): " + toHex(fromRadio));
        return fromRadio;
    }

    /** DLI always uses preambles. */
    @Override
    public boolean lastResponseHadPreamble() {
        return true;
    }

    /** No-op: UDP is connectionless, no queues to flush. */
    @Override
    public void clearQueues() { }

    // ── Standard session helpers ──────────────────────────────────────────────

    private void sendReadyRequest() throws IOException {
        dbg("DLI TX: ReadyRequest");
        SessionControl cmd = new SessionControl();
        cmd.sessionControlOpcode = SessionControl.ScOpcode.ReadyRequest;
        cmd.sourceDeviceType     = SessionControl.ScSourceDeviceType.Kfd;

        byte[] fromRadio = txRxSession(cmd);

        KmmFrame rsp = new KmmFrame(true, fromRadio);
        if (rsp.kmmBody instanceof SessionControl) {
            SessionControl sc = (SessionControl) rsp.kmmBody;
            dbg("DLI RX: " + sc.sessionControlOpcode);
            if (sc.sessionControlOpcode != SessionControl.ScOpcode.ReadyGeneralMode) {
                throw new IOException("Expected ReadyGeneralMode, got: " + sc.sessionControlOpcode);
            }
        } else {
            throw new IOException("Unexpected response to ReadyRequest: " + rsp.kmmBody.getClass().getSimpleName());
        }
    }

    private void sendTransferDone() throws IOException {
        dbg("DLI TX: TransferDone");
        SessionControl cmd = new SessionControl();
        cmd.sessionControlOpcode = SessionControl.ScOpcode.TransferDone;
        cmd.sourceDeviceType     = SessionControl.ScSourceDeviceType.Kfd;

        byte[] fromRadio = txRxSession(cmd);

        KmmFrame rsp = new KmmFrame(true, fromRadio);
        if (rsp.kmmBody instanceof SessionControl) {
            SessionControl sc = (SessionControl) rsp.kmmBody;
            dbg("DLI RX: " + sc.sessionControlOpcode);
            if (sc.sessionControlOpcode != SessionControl.ScOpcode.TransferDone) {
                throw new IOException("Expected TransferDone echo, got: " + sc.sessionControlOpcode);
            }
        } else {
            throw new IOException("Unexpected response to TransferDone");
        }
    }

    private void sendEndSession() throws IOException {
        dbg("DLI TX: EndSession");
        SessionControl cmd = new SessionControl();
        cmd.sessionControlOpcode = SessionControl.ScOpcode.EndSession;
        cmd.sourceDeviceType     = SessionControl.ScSourceDeviceType.Kfd;

        byte[] fromRadio = txRxSession(cmd);

        KmmFrame rsp = new KmmFrame(true, fromRadio);
        if (rsp.kmmBody instanceof SessionControl) {
            SessionControl sc = (SessionControl) rsp.kmmBody;
            dbg("DLI RX: " + sc.sessionControlOpcode);
            if (sc.sessionControlOpcode != SessionControl.ScOpcode.EndSessionAck) {
                throw new IOException("Expected EndSessionAck, got: " + sc.sessionControlOpcode);
            }
        } else {
            throw new IOException("Unexpected response to EndSession");
        }
    }

    private void sendDisconnect() throws IOException {
        dbg("DLI TX: Disconnect");
        SessionControl cmd = new SessionControl();
        cmd.sessionControlOpcode = SessionControl.ScOpcode.Disconnect;
        cmd.sourceDeviceType     = SessionControl.ScSourceDeviceType.Kfd;

        byte[] fromRadio = txRxSession(cmd);

        KmmFrame rsp = new KmmFrame(true, fromRadio);
        if (rsp.kmmBody instanceof SessionControl) {
            SessionControl sc = (SessionControl) rsp.kmmBody;
            dbg("DLI RX: " + sc.sessionControlOpcode);
            if (sc.sessionControlOpcode != SessionControl.ScOpcode.DisconnectAck) {
                throw new IOException("Expected DisconnectAck, got: " + sc.sessionControlOpcode);
            }
        } else {
            throw new IOException("Unexpected response to Disconnect");
        }
    }

    // ── Motorola session helpers ──────────────────────────────────────────────

    private void mfid90SendConnect() throws IOException {
        dbg("DLI TX: Mfid90 Connect");
        Mfid90SessionControlVer1 cmd = new Mfid90SessionControlVer1();
        cmd.sessionControlOpcode  = Mfid90SessionControlVer1.ScOpcode.Connect;
        cmd.sourceDeviceType      = Mfid90SessionControlVer1.ScSourceDeviceType.Kfd;
        cmd.isSessionTypeIncluded = false;

        byte[] fromRadio = txRxSession(cmd);

        KmmFrame rsp = new KmmFrame(true, fromRadio);
        if (rsp.kmmBody instanceof Mfid90SessionControlVer1) {
            Mfid90SessionControlVer1 sc = (Mfid90SessionControlVer1) rsp.kmmBody;
            dbg("DLI RX: Mfid90 " + sc.sessionControlOpcode);
            if (sc.sessionControlOpcode != Mfid90SessionControlVer1.ScOpcode.ConnectAck) {
                throw new IOException("Expected ConnectAck, got: " + sc.sessionControlOpcode);
            }
        } else {
            throw new IOException("Unexpected response to Connect");
        }
    }

    private void mfid90SendBeginSession() throws IOException {
        dbg("DLI TX: Mfid90 BeginSession");
        Mfid90SessionControlVer1 cmd = new Mfid90SessionControlVer1();
        cmd.sessionControlOpcode  = Mfid90SessionControlVer1.ScOpcode.BeginSession;
        cmd.sourceDeviceType      = Mfid90SessionControlVer1.ScSourceDeviceType.Kfd;
        cmd.isSessionTypeIncluded = true;
        cmd.sessionType           = Mfid90SessionControlVer1.ScSessionType.KeyFill;

        byte[] fromRadio = txRxSession(cmd);

        KmmFrame rsp = new KmmFrame(true, fromRadio);
        if (rsp.kmmBody instanceof Mfid90SessionControlVer1) {
            Mfid90SessionControlVer1 sc = (Mfid90SessionControlVer1) rsp.kmmBody;
            dbg("DLI RX: Mfid90 " + sc.sessionControlOpcode);
            if (sc.sessionControlOpcode != Mfid90SessionControlVer1.ScOpcode.BeginSessionAck) {
                throw new IOException("Expected BeginSessionAck, got: " + sc.sessionControlOpcode);
            }
        } else {
            throw new IOException("Unexpected response to BeginSession");
        }
    }

    private void mfid90SendTransferDone() throws IOException {
        dbg("DLI TX: Mfid90 TransferDone");
        Mfid90SessionControlVer1 cmd = new Mfid90SessionControlVer1();
        cmd.sessionControlOpcode  = Mfid90SessionControlVer1.ScOpcode.TransferDone;
        cmd.sourceDeviceType      = Mfid90SessionControlVer1.ScSourceDeviceType.Kfd;
        cmd.isSessionTypeIncluded = false;

        byte[] fromRadio = txRxSession(cmd);

        KmmFrame rsp = new KmmFrame(true, fromRadio);
        if (rsp.kmmBody instanceof Mfid90SessionControlVer1) {
            Mfid90SessionControlVer1 sc = (Mfid90SessionControlVer1) rsp.kmmBody;
            dbg("DLI RX: Mfid90 " + sc.sessionControlOpcode);
            if (sc.sessionControlOpcode != Mfid90SessionControlVer1.ScOpcode.TransferDone) {
                throw new IOException("Expected TransferDone echo, got: " + sc.sessionControlOpcode);
            }
        } else {
            throw new IOException("Unexpected response to Mfid90 TransferDone");
        }
    }

    private void mfid90SendEndSession() throws IOException {
        dbg("DLI TX: Mfid90 EndSession");
        Mfid90SessionControlVer1 cmd = new Mfid90SessionControlVer1();
        cmd.sessionControlOpcode  = Mfid90SessionControlVer1.ScOpcode.EndSession;
        cmd.sourceDeviceType      = Mfid90SessionControlVer1.ScSourceDeviceType.Kfd;
        cmd.isSessionTypeIncluded = true;
        cmd.sessionType           = Mfid90SessionControlVer1.ScSessionType.KeyFill;

        byte[] fromRadio = txRxSession(cmd);

        KmmFrame rsp = new KmmFrame(true, fromRadio);
        if (rsp.kmmBody instanceof Mfid90SessionControlVer1) {
            Mfid90SessionControlVer1 sc = (Mfid90SessionControlVer1) rsp.kmmBody;
            dbg("DLI RX: Mfid90 " + sc.sessionControlOpcode);
            if (sc.sessionControlOpcode != Mfid90SessionControlVer1.ScOpcode.EndSessionAck) {
                throw new IOException("Expected EndSessionAck, got: " + sc.sessionControlOpcode);
            }
        } else {
            throw new IOException("Unexpected response to Mfid90 EndSession");
        }
    }

    private void mfid90SendDisconnect() throws IOException {
        dbg("DLI TX: Mfid90 Disconnect");
        Mfid90SessionControlVer1 cmd = new Mfid90SessionControlVer1();
        cmd.sessionControlOpcode  = Mfid90SessionControlVer1.ScOpcode.Disconnect;
        cmd.sourceDeviceType      = Mfid90SessionControlVer1.ScSourceDeviceType.Kfd;
        cmd.isSessionTypeIncluded = false;

        byte[] fromRadio = txRxSession(cmd);

        KmmFrame rsp = new KmmFrame(true, fromRadio);
        if (rsp.kmmBody instanceof Mfid90SessionControlVer1) {
            Mfid90SessionControlVer1 sc = (Mfid90SessionControlVer1) rsp.kmmBody;
            dbg("DLI RX: Mfid90 " + sc.sessionControlOpcode);
            if (sc.sessionControlOpcode != Mfid90SessionControlVer1.ScOpcode.DisconnectAck) {
                throw new IOException("Expected DisconnectAck, got: " + sc.sessionControlOpcode);
            }
        } else {
            throw new IOException("Unexpected response to Mfid90 Disconnect");
        }
    }

    // ── Internal helper ───────────────────────────────────────────────────────

    /** Serialize a session-control body with preamble and do one UDP round trip. */
    private byte[] txRxSession(KmmBody body) throws IOException {
        byte[] toRadio = new KmmFrame(body).toBytesWithPreamble(mfid);
        dbg("DLI session TX (" + toRadio.length + " bytes): " + toHex(toRadio));
        byte[] fromRadio = udp.txRx(toRadio);
        dbg("DLI session RX (" + fromRadio.length + " bytes): " + toHex(fromRadio));
        return fromRadio;
    }
}
