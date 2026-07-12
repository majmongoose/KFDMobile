/*
 * Copyright 2026 Charles Bricker
 * Licensed under the MIT License (see project root).
 */
package com.kfdtool.android.usb.rndis;

import android.util.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

/**
 * Minimal DHCP client: DISCOVER → OFFER → REQUEST → ACK.
 *
 * <p>Assumes the peer (APX radio) runs a DHCP server on its USB RNDIS link.
 * Learns our IP (yiaddr) and the server IP (siaddr / option-54), which we use
 * as the DLI target host.
 *
 * <p>Runs directly on top of {@link RndisSession} + {@link NetProto} — no OS
 * sockets involved.
 */
final class DhcpClient {

    private static final String TAG = "DhcpClient";
    private static final int    CLIENT_PORT = 68;
    private static final int    SERVER_PORT = 67;
    private static final int    ATTEMPT_TIMEOUT_MS = 3000;
    private static final int    MAX_ATTEMPTS       = 4;

    private static final int MAGIC_COOKIE = 0x63825363;

    static class Lease {
        byte[] clientIp = new byte[4];
        byte[] serverIp = new byte[4]; // DHCP server = the radio
        byte[] serverMac;              // learned from Ethernet src of ACK
    }

    private final RndisSession rndis;
    private final byte[]       myMac;

    DhcpClient(RndisSession rndis, byte[] myMac) {
        this.rndis = rndis;
        this.myMac = myMac;
    }

    /**
     * Runs the DHCP exchange and returns the lease, or throws on failure.
     * The returned lease's {@code serverMac} is populated with the ACK's Ethernet source.
     */
    Lease obtainLease() throws IOException {
        int xid = new Random().nextInt();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                Log.d(TAG, "DHCP attempt " + (attempt + 1) + " xid=0x" + Integer.toHexString(xid));
                sendDiscover(xid);
                Reply offer = waitForReply(xid, /*wantType*/ 2, ATTEMPT_TIMEOUT_MS);
                if (offer == null) { xid++; continue; }

                sendRequest(xid, offer.yiaddr, offer.serverId);
                Reply ack = waitForReply(xid, /*wantType*/ 5, ATTEMPT_TIMEOUT_MS);
                if (ack == null) { xid++; continue; }
                Log.i(TAG, "DHCP ACK: our IP=" + ByteUtil.ipToStr(ack.yiaddr)
                        + " server=" + ByteUtil.ipToStr(ack.serverId));

                Lease lease = new Lease();
                lease.clientIp  = ack.yiaddr;
                lease.serverIp  = ack.serverId;
                lease.serverMac = ack.ethSrcMac;
                return lease;
            } catch (IOException e) {
                Log.w(TAG, "DHCP attempt failed: " + e.getMessage());
                xid++;
            }
        }
        throw new IOException("DHCP failed after " + MAX_ATTEMPTS + " attempts");
    }

    // ── Send ───────────────────────────────────────────────────────────────

    private void sendDiscover(int xid) throws IOException {
        byte[] opts = new byte[] {
                53, 1, 1,      // DHCP Message Type = DISCOVER
                55, 4, 1, 3, 6, 54, // parameter request list: subnet, router, DNS, server-id
                (byte) 0xFF    // END
        };
        byte[] payload = buildBootp(xid, NetProto.IP_ZERO, opts);
        byte[] udp = NetProto.buildUdp(NetProto.IP_ZERO, NetProto.IP_BROADCAST,
                CLIENT_PORT, SERVER_PORT, payload);
        byte[] ip  = NetProto.buildIpv4(NetProto.IP_ZERO, NetProto.IP_BROADCAST,
                NetProto.IP_PROTO_UDP, udp, xid & 0xFFFF);
        byte[] eth = NetProto.buildEthernet(NetProto.MAC_BROADCAST, myMac, NetProto.ETHERTYPE_IPV4, ip);
        rndis.sendPacket(eth);
    }

    private void sendRequest(int xid, byte[] requestedIp, byte[] serverId) throws IOException {
        byte[] opts = new byte[] {
                53, 1, 3,       // DHCP Message Type = REQUEST
                50, 4, requestedIp[0], requestedIp[1], requestedIp[2], requestedIp[3], // Requested IP
                54, 4, serverId[0],    serverId[1],    serverId[2],    serverId[3],    // Server-ID
                55, 4, 1, 3, 6, 54,
                (byte) 0xFF
        };
        byte[] payload = buildBootp(xid, NetProto.IP_ZERO, opts);
        byte[] udp = NetProto.buildUdp(NetProto.IP_ZERO, NetProto.IP_BROADCAST,
                CLIENT_PORT, SERVER_PORT, payload);
        byte[] ip  = NetProto.buildIpv4(NetProto.IP_ZERO, NetProto.IP_BROADCAST,
                NetProto.IP_PROTO_UDP, udp, (xid + 1) & 0xFFFF);
        byte[] eth = NetProto.buildEthernet(NetProto.MAC_BROADCAST, myMac, NetProto.ETHERTYPE_IPV4, ip);
        rndis.sendPacket(eth);
    }

    /** BOOTP fixed fields (240 bytes) + options. */
    private byte[] buildBootp(int xid, byte[] ciaddr, byte[] options) {
        byte[] b = new byte[240 + options.length];
        b[0] = 1;                                    // op = BOOTREQUEST
        b[1] = 1;                                    // htype = Ethernet
        b[2] = 6;                                    // hlen
        b[3] = 0;                                    // hops
        ByteUtil.putBE32(b, 4, xid);
        // secs (2) @ 8, flags (2) @ 10
        ByteUtil.putBE16(b, 10, (short) 0x8000);     // broadcast flag
        System.arraycopy(ciaddr, 0, b, 12, 4);
        // yiaddr @16, siaddr @20, giaddr @24 all zero
        System.arraycopy(myMac, 0, b, 28, 6);        // chaddr
        // sname (64) @44, file (128) @108 all zero
        ByteUtil.putBE32(b, 236, MAGIC_COOKIE);
        System.arraycopy(options, 0, b, 240, options.length);
        return b;
    }

    // ── Receive ────────────────────────────────────────────────────────────

    private static class Reply {
        byte[] yiaddr   = new byte[4];
        byte[] serverId = new byte[4];
        int    msgType;   // 2=OFFER, 5=ACK, 6=NAK
        byte[] ethSrcMac = new byte[6];
    }

    private Reply waitForReply(int xid, int wantMsgType, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int remaining = (int) (deadline - System.currentTimeMillis());
            byte[] eth;
            try {
                eth = rndis.receivePacket(Math.max(200, remaining));
            } catch (IOException e) {
                continue; // usually just a bulkIn timeout
            }
            if (eth == null) continue;
            NetProto.Eth ef = NetProto.parseEthernet(eth);
            if (ef == null || ef.etherType != NetProto.ETHERTYPE_IPV4) continue;

            NetProto.Ip ip = NetProto.parseIpv4(ef.payload);
            if (ip == null || ip.protocol != NetProto.IP_PROTO_UDP) continue;

            NetProto.Udp udp = NetProto.parseUdp(ip.payload);
            if (udp == null || udp.dstPort != CLIENT_PORT || udp.srcPort != SERVER_PORT) continue;

            Reply r = parseBootpReply(udp.data);
            if (r == null) continue;
            if (r.msgType != wantMsgType) continue;
            System.arraycopy(ef.srcMac, 0, r.ethSrcMac, 0, 6);
            // Verify xid to prevent stray-reply confusion (BOOTP xid is at offset 4)
            // parseBootpReply already checked it via caller
            return r;
        }
        return null;
    }

    private Reply parseBootpReply(byte[] data) {
        if (data == null || data.length < 240) return null;
        if (data[0] != 2) return null;                          // op = BOOTREPLY
        if (ByteUtil.getBE32(data, 236) != MAGIC_COOKIE) return null;
        Reply r = new Reply();
        System.arraycopy(data, 16, r.yiaddr, 0, 4);             // yiaddr
        System.arraycopy(data, 20, r.serverId, 0, 4);           // siaddr (default; option-54 overrides)
        int i = 240;
        while (i < data.length) {
            int code = data[i] & 0xFF;
            if (code == 0)   { i++; continue; }
            if (code == 255) break;
            if (i + 1 >= data.length) break;
            int len = data[i + 1] & 0xFF;
            int v   = i + 2;
            if (v + len > data.length) break;
            switch (code) {
                case 53: // DHCP Message Type
                    r.msgType = data[v] & 0xFF;
                    break;
                case 54: // Server Identifier (preferred over siaddr)
                    if (len == 4) System.arraycopy(data, v, r.serverId, 0, 4);
                    break;
                default:
                    break;
            }
            i = v + len;
        }
        return r;
    }
}
