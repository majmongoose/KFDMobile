/*
 * Copyright 2026 Charles Bricker
 * Licensed under the MIT License (see project root).
 */
package com.sentinelcomms.kfdmobile.usb.rndis;

import android.util.Log;

import com.sentinelcomms.kfdmobile.BuildConfig;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RNDIS message layer.
 *
 * <p>Control messages (INITIALIZE, QUERY, SET, KEEPALIVE) go over the USB
 * control endpoint using the SEND_ENCAPSULATED / GET_ENCAPSULATED class
 * requests. Data packets (PACKET_MSG) go over the bulk endpoints on the
 * data interface.
 */
public class RndisSession {

    private static final String TAG = "RndisSession";
    private static final int    CTRL_TIMEOUT_MS = 2000;
    private static final int    CTRL_BUFFER     = 1025; // typical response upper bound
    private static final int    BULK_BUFFER     = 2048;

    private final UsbRndisManager mgr;
    private final AtomicInteger   reqId = new AtomicInteger(1);
    private       int             maxTransferSize = Rndis.MAX_TRANSFER_SIZE;

    public RndisSession(UsbRndisManager mgr) { this.mgr = mgr; }

    // ── Control ────────────────────────────────────────────────────────────

    /** RNDIS_INITIALIZE_MSG — must be first thing after opening the device. */
    public void initialize() throws IOException {
        byte[] msg = new byte[24];
        int rid = reqId.getAndIncrement();
        ByteUtil.putLE32(msg,  0, Rndis.MSG_INITIALIZE);
        ByteUtil.putLE32(msg,  4, msg.length);
        ByteUtil.putLE32(msg,  8, rid);
        ByteUtil.putLE32(msg, 12, Rndis.MAJOR_VERSION);
        ByteUtil.putLE32(msg, 16, Rndis.MINOR_VERSION);
        ByteUtil.putLE32(msg, 20, Rndis.MAX_TRANSFER_SIZE);

        byte[] resp = controlTransfer(msg);
        // INITIALIZE_CMPLT layout (offsets, all LE32):
        //   0 MessageType   4 MessageLength   8 RequestID  12 Status
        //  16 MajorVersion 20 MinorVersion   24 DeviceFlags 28 Medium
        //  32 MaxPacketsPerTransfer  36 MaxTransferSize  40 PacketAlignmentFactor …
        // The spec-full response is 52 bytes but some firmwares reply shorter;
        // as long as MessageType/RequestID/Status are present, that's enough to
        // proceed. Fall back to a sensible default for maxTransferSize.
        if (resp.length < 16) throw new IOException(
                "INITIALIZE_CMPLT too short: " + resp.length);
        int type   = ByteUtil.getLE32(resp, 0);
        int rrid   = ByteUtil.getLE32(resp, 8);
        int status = ByteUtil.getLE32(resp, 12);
        if (type != Rndis.MSG_INITIALIZE_C) throw new IOException("Unexpected msg type 0x"
                + Integer.toHexString(type));
        if (rrid != rid) throw new IOException("INITIALIZE_CMPLT request-id mismatch");
        if (status != Rndis.STATUS_SUCCESS) throw new IOException("INITIALIZE failed status 0x"
                + Integer.toHexString(status));
        this.maxTransferSize = resp.length >= 40
                ? ByteUtil.getLE32(resp, 36)
                : Rndis.MAX_TRANSFER_SIZE;
        Log.i(TAG, "RNDIS initialized (maxTransfer=" + maxTransferSize + ")");
    }

    /** RNDIS_QUERY_MSG — returns just the OID data (no message header). */
    public byte[] queryOid(int oid) throws IOException {
        byte[] msg = new byte[28];
        int rid = reqId.getAndIncrement();
        ByteUtil.putLE32(msg,  0, Rndis.MSG_QUERY);
        ByteUtil.putLE32(msg,  4, msg.length);
        ByteUtil.putLE32(msg,  8, rid);
        ByteUtil.putLE32(msg, 12, oid);
        ByteUtil.putLE32(msg, 16, 0);   // InformationBufferLength
        ByteUtil.putLE32(msg, 20, 0);   // InformationBufferOffset
        ByteUtil.putLE32(msg, 24, 0);   // reserved

        byte[] resp = controlTransfer(msg);
        if (resp.length < 24) throw new IOException("QUERY_CMPLT too short");
        int type   = ByteUtil.getLE32(resp, 0);
        int rrid   = ByteUtil.getLE32(resp, 8);
        int status = ByteUtil.getLE32(resp, 12);
        if (type != Rndis.MSG_QUERY_C) throw new IOException("Unexpected msg type");
        if (rrid != rid) throw new IOException("QUERY_CMPLT request-id mismatch");
        if (status != Rndis.STATUS_SUCCESS) throw new IOException(
                "QUERY OID 0x" + Integer.toHexString(oid) + " failed status 0x"
                        + Integer.toHexString(status));
        int infoLen = ByteUtil.getLE32(resp, 16);
        int infoOff = ByteUtil.getLE32(resp, 20);
        // infoOff is relative to the start of RequestID field (offset 8 in the message)
        int abs = 8 + infoOff;
        if (abs < 0 || abs + infoLen > resp.length) throw new IOException(
                "QUERY_CMPLT info span out of range");
        return Arrays.copyOfRange(resp, abs, abs + infoLen);
    }

    /** RNDIS_SET_MSG. */
    public void setOid(int oid, byte[] data) throws IOException {
        byte[] msg = new byte[28 + data.length];
        int rid = reqId.getAndIncrement();
        ByteUtil.putLE32(msg,  0, Rndis.MSG_SET);
        ByteUtil.putLE32(msg,  4, msg.length);
        ByteUtil.putLE32(msg,  8, rid);
        ByteUtil.putLE32(msg, 12, oid);
        ByteUtil.putLE32(msg, 16, data.length);
        ByteUtil.putLE32(msg, 20, 20); // offset relative to RequestID → 8 + 20 = 28
        ByteUtil.putLE32(msg, 24, 0);
        System.arraycopy(data, 0, msg, 28, data.length);

        byte[] resp = controlTransfer(msg);
        if (resp.length < 16) throw new IOException("SET_CMPLT too short");
        int type   = ByteUtil.getLE32(resp, 0);
        int rrid   = ByteUtil.getLE32(resp, 8);
        int status = ByteUtil.getLE32(resp, 12);
        if (type != Rndis.MSG_SET_C) throw new IOException("Unexpected msg type");
        if (rrid != rid) throw new IOException("SET_CMPLT request-id mismatch");
        if (status != Rndis.STATUS_SUCCESS) throw new IOException(
                "SET OID 0x" + Integer.toHexString(oid) + " failed status 0x"
                        + Integer.toHexString(status));
    }

    /** Bring the "link" up by permitting directed+broadcast frames to us. */
    public void enableDefaultPacketFilter() throws IOException {
        byte[] filter = new byte[4];
        ByteUtil.putLE32(filter, 0, Rndis.PF_DIRECTED | Rndis.PF_BROADCAST);
        setOid(Rndis.OID_GEN_CURRENT_PACKET_FILTER, filter);
    }

    public byte[] queryPermanentMac() throws IOException {
        byte[] mac = queryOid(Rndis.OID_802_3_PERMANENT_ADDRESS);
        if (mac.length < 6) throw new IOException("Permanent MAC too short");
        return Arrays.copyOfRange(mac, 0, 6);
    }

    /**
     * One control round-trip: SEND_ENCAPSULATED_COMMAND, then
     * GET_ENCAPSULATED_RESPONSE. Mirrors Linux's rndis_host approach:
     * poll GET_ENCAPSULATED with short sleeps, and *optionally* consult the
     * interrupt-IN notification endpoint if the poll STALLs — some firmwares
     * only prime the response after firing the notification.
     */
    private byte[] controlTransfer(byte[] request) throws IOException {
        int reqType = request.length >= 4 ? ByteUtil.getLE32(request, 0) : 0;
        mgr.controlOut(request, CTRL_TIMEOUT_MS);

        int expectedReplyType = reqType | 0x80000000;   // RNDIS completion = request | high bit
        byte[] buf = new byte[CTRL_BUFFER];
        long deadline = System.currentTimeMillis() + CTRL_TIMEOUT_MS;
        boolean triedNotification = false;

        while (System.currentTimeMillis() < deadline) {
            int n;
            try {
                n = mgr.controlIn(buf, 200);
            } catch (IOException stalled) {
                n = -1;
            }
            if (n >= 4) {
                byte[] resp = Arrays.copyOf(buf, n);
                int replyType = ByteUtil.getLE32(resp, 0);
                if (replyType == expectedReplyType) return resp;
                if (BuildConfig.DEBUG) Log.d(TAG, "Skipping unsolicited RNDIS msg type=0x"
                        + Integer.toHexString(replyType));
                continue;
            }

            if (!triedNotification) {
                triedNotification = true;
                try { mgr.waitForResponseNotification(500); } catch (IOException ignored) {}
            } else {
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted waiting for RNDIS response");
                }
            }
        }
        throw new IOException("No RNDIS response (timed out)");
    }

    // ── Data ───────────────────────────────────────────────────────────────

    /** Wrap an Ethernet frame in RNDIS_PACKET_MSG and send it over bulk-out. */
    public void sendPacket(byte[] ethFrame) throws IOException {
        byte[] msg = new byte[44 + ethFrame.length];
        ByteUtil.putLE32(msg,  0, Rndis.MSG_PACKET);
        ByteUtil.putLE32(msg,  4, msg.length);
        ByteUtil.putLE32(msg,  8, 36);           // DataOffset (from start of DataOffset field → 8+36=44)
        ByteUtil.putLE32(msg, 12, ethFrame.length);
        // Remaining fields (OOB and PerPacketInfo) all zero
        System.arraycopy(ethFrame, 0, msg, 44, ethFrame.length);
        mgr.bulkOut(msg, 1000);
    }

    /**
     * Receive one Ethernet frame from bulk-in (blocking with timeout).
     * Returns null on timeout. Skips non-PACKET messages.
     */
    public byte[] receivePacket(int timeoutMs) throws IOException {
        byte[] buf = new byte[BULK_BUFFER];
        int n = mgr.bulkIn(buf, timeoutMs);
        if (n < 8) return null;
        int type = ByteUtil.getLE32(buf, 0);
        if (type != Rndis.MSG_PACKET) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Skipping non-packet RNDIS msg type 0x" + Integer.toHexString(type));
            return null;
        }
        int totalLen = ByteUtil.getLE32(buf,  4);
        int dataOff  = ByteUtil.getLE32(buf,  8);
        int dataLen  = ByteUtil.getLE32(buf, 12);
        int abs = 8 + dataOff;
        if (totalLen > n || abs < 0 || abs + dataLen > n) {
            Log.w(TAG, "PACKET_MSG span invalid");
            return null;
        }
        return Arrays.copyOfRange(buf, abs, abs + dataLen);
    }
}
