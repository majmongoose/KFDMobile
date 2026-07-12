/*
 * Copyright 2026 Charles Bricker
 * Licensed under the MIT License (see project root).
 */
package com.kfdtool.android.usb.rndis;

import java.util.Arrays;

/**
 * Minimal userspace Ethernet II + ARP + IPv4 + UDP layer.
 * Every helper is stateless; wire it up in {@link UsbRndisUdpTransport}.
 */
final class NetProto {

    private NetProto() {}

    static final int ETHERTYPE_IPV4 = 0x0800;
    static final int ETHERTYPE_ARP  = 0x0806;

    static final int IP_PROTO_UDP   = 17;
    static final int IP_PROTO_TCP   = 6;

    static final int TCP_FIN = 0x01;
    static final int TCP_SYN = 0x02;
    static final int TCP_RST = 0x04;
    static final int TCP_PSH = 0x08;
    static final int TCP_ACK = 0x10;

    static final int ARP_OP_REQUEST = 1;
    static final int ARP_OP_REPLY   = 2;

    static final byte[] MAC_BROADCAST = {(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF};
    static final byte[] IP_BROADCAST  = {(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF};
    static final byte[] IP_ZERO       = {0, 0, 0, 0};

    // ── Ethernet ───────────────────────────────────────────────────────────

    static byte[] buildEthernet(byte[] dstMac, byte[] srcMac, int etherType, byte[] payload) {
        byte[] frame = new byte[14 + payload.length];
        System.arraycopy(dstMac, 0, frame, 0, 6);
        System.arraycopy(srcMac, 0, frame, 6, 6);
        ByteUtil.putBE16(frame, 12, etherType);
        System.arraycopy(payload, 0, frame, 14, payload.length);
        return frame;
    }

    /** Parsed Ethernet frame view — no allocation for payload beyond a slice. */
    static class Eth {
        byte[] dstMac = new byte[6];
        byte[] srcMac = new byte[6];
        int etherType;
        byte[] payload;
    }

    static Eth parseEthernet(byte[] frame) {
        if (frame == null || frame.length < 14) return null;
        Eth e = new Eth();
        System.arraycopy(frame, 0, e.dstMac, 0, 6);
        System.arraycopy(frame, 6, e.srcMac, 0, 6);
        e.etherType = ByteUtil.getBE16(frame, 12);
        e.payload   = Arrays.copyOfRange(frame, 14, frame.length);
        return e;
    }

    // ── ARP ────────────────────────────────────────────────────────────────

    static byte[] buildArp(int opcode, byte[] senderMac, byte[] senderIp,
                                        byte[] targetMac, byte[] targetIp) {
        byte[] arp = new byte[28];
        ByteUtil.putBE16(arp, 0, 1);              // htype = Ethernet
        ByteUtil.putBE16(arp, 2, ETHERTYPE_IPV4); // ptype = IPv4
        arp[4] = 6;                                // hlen
        arp[5] = 4;                                // plen
        ByteUtil.putBE16(arp, 6, opcode);
        System.arraycopy(senderMac, 0, arp,  8, 6);
        System.arraycopy(senderIp,  0, arp, 14, 4);
        System.arraycopy(targetMac, 0, arp, 18, 6);
        System.arraycopy(targetIp,  0, arp, 24, 4);
        return arp;
    }

    static class Arp {
        int    opcode;
        byte[] senderMac = new byte[6];
        byte[] senderIp  = new byte[4];
        byte[] targetMac = new byte[6];
        byte[] targetIp  = new byte[4];
    }

    static Arp parseArp(byte[] p) {
        if (p == null || p.length < 28) return null;
        Arp a = new Arp();
        a.opcode = ByteUtil.getBE16(p, 6);
        System.arraycopy(p,  8, a.senderMac, 0, 6);
        System.arraycopy(p, 14, a.senderIp,  0, 4);
        System.arraycopy(p, 18, a.targetMac, 0, 6);
        System.arraycopy(p, 24, a.targetIp,  0, 4);
        return a;
    }

    // ── IPv4 ───────────────────────────────────────────────────────────────

    /** Build an IPv4 packet (no options, don't-fragment set). */
    static byte[] buildIpv4(byte[] srcIp, byte[] dstIp, int protocol, byte[] payload, int identification) {
        int total = 20 + payload.length;
        byte[] ip = new byte[total];
        ip[0] = 0x45;                              // version 4, IHL 5
        ip[1] = 0;                                  // DSCP/ECN
        ByteUtil.putBE16(ip, 2, total);
        ByteUtil.putBE16(ip, 4, identification & 0xFFFF);
        ByteUtil.putBE16(ip, 6, 0x4000);            // DF flag, no fragment
        ip[8] = 64;                                 // TTL
        ip[9] = (byte) protocol;
        // header checksum @ 10..11 filled below
        System.arraycopy(srcIp, 0, ip, 12, 4);
        System.arraycopy(dstIp, 0, ip, 16, 4);
        System.arraycopy(payload, 0, ip, 20, payload.length);
        int cksum = checksum16(ip, 0, 20);
        ByteUtil.putBE16(ip, 10, cksum);
        return ip;
    }

    static class Ip {
        byte[] srcIp = new byte[4];
        byte[] dstIp = new byte[4];
        int    protocol;
        int    headerLen;
        byte[] payload;
    }

    static Ip parseIpv4(byte[] p) {
        if (p == null || p.length < 20) return null;
        int vi = p[0] & 0xFF;
        if ((vi >>> 4) != 4) return null;
        int ihl = (vi & 0x0F) * 4;
        if (ihl < 20 || ihl > p.length) return null;
        int total = ByteUtil.getBE16(p, 2);
        if (total > p.length) total = p.length;
        Ip ip = new Ip();
        ip.headerLen = ihl;
        ip.protocol  = p[9] & 0xFF;
        System.arraycopy(p, 12, ip.srcIp, 0, 4);
        System.arraycopy(p, 16, ip.dstIp, 0, 4);
        ip.payload = Arrays.copyOfRange(p, ihl, total);
        return ip;
    }

    // ── UDP ────────────────────────────────────────────────────────────────

    static byte[] buildUdp(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort, byte[] data) {
        byte[] udp = new byte[8 + data.length];
        ByteUtil.putBE16(udp, 0, srcPort);
        ByteUtil.putBE16(udp, 2, dstPort);
        ByteUtil.putBE16(udp, 4, udp.length);
        ByteUtil.putBE16(udp, 6, 0);
        System.arraycopy(data, 0, udp, 8, data.length);

        // UDP checksum with IPv4 pseudo-header
        byte[] pseudo = new byte[12];
        System.arraycopy(srcIp, 0, pseudo, 0, 4);
        System.arraycopy(dstIp, 0, pseudo, 4, 4);
        pseudo[8] = 0;
        pseudo[9] = IP_PROTO_UDP;
        ByteUtil.putBE16(pseudo, 10, udp.length);
        int sum = checksum16Sum(pseudo, 0, pseudo.length, 0);
        sum = checksum16Sum(udp, 0, udp.length, sum);
        int c = ~foldCarry(sum) & 0xFFFF;
        if (c == 0) c = 0xFFFF; // 0 is reserved to mean "no checksum"
        ByteUtil.putBE16(udp, 6, c);
        return udp;
    }

    static class Udp {
        int srcPort, dstPort;
        byte[] data;
    }

    static Udp parseUdp(byte[] p) {
        if (p == null || p.length < 8) return null;
        Udp u = new Udp();
        u.srcPort = ByteUtil.getBE16(p, 0);
        u.dstPort = ByteUtil.getBE16(p, 2);
        int len   = ByteUtil.getBE16(p, 4);
        if (len < 8 || len > p.length) len = p.length;
        u.data = Arrays.copyOfRange(p, 8, len);
        return u;
    }

    // ── TCP ────────────────────────────────────────────────────────────────

    /**
     * Build a TCP segment. {@code options} may be null; if non-null must be a
     * multiple of 4 bytes long. {@code data} may be empty.
     */
    static byte[] buildTcp(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort,
                           long seq, long ack, int flags, int windowSize,
                           byte[] options, byte[] data) {
        int optLen = options != null ? options.length : 0;
        if ((optLen & 3) != 0) throw new IllegalArgumentException("options not 4-aligned");
        int hdrLen = 20 + optLen;
        byte[] seg = new byte[hdrLen + data.length];

        ByteUtil.putBE16(seg,  0, srcPort);
        ByteUtil.putBE16(seg,  2, dstPort);
        ByteUtil.putBE32(seg,  4, (int) (seq & 0xFFFFFFFFL));
        ByteUtil.putBE32(seg,  8, (int) (ack & 0xFFFFFFFFL));
        int dataOff = (hdrLen / 4) << 4;               // upper 4 bits
        seg[12] = (byte) dataOff;
        seg[13] = (byte) (flags & 0x3F);
        ByteUtil.putBE16(seg, 14, windowSize);
        ByteUtil.putBE16(seg, 16, 0);                   // checksum placeholder
        ByteUtil.putBE16(seg, 18, 0);                   // urgent
        if (optLen > 0) System.arraycopy(options, 0, seg, 20, optLen);
        System.arraycopy(data, 0, seg, hdrLen, data.length);

        // TCP checksum with IPv4 pseudo-header
        byte[] pseudo = new byte[12];
        System.arraycopy(srcIp, 0, pseudo, 0, 4);
        System.arraycopy(dstIp, 0, pseudo, 4, 4);
        pseudo[8] = 0;
        pseudo[9] = IP_PROTO_TCP;
        ByteUtil.putBE16(pseudo, 10, seg.length);
        int sum = checksum16Sum(pseudo, 0, pseudo.length, 0);
        sum = checksum16Sum(seg, 0, seg.length, sum);
        ByteUtil.putBE16(seg, 16, (~foldCarry(sum)) & 0xFFFF);
        return seg;
    }

    static class Tcp {
        int    srcPort, dstPort;
        long   seq, ack;
        int    flags;
        int    hdrLen;
        byte[] data;
    }

    static Tcp parseTcp(byte[] p) {
        if (p == null || p.length < 20) return null;
        Tcp t = new Tcp();
        t.srcPort = ByteUtil.getBE16(p, 0);
        t.dstPort = ByteUtil.getBE16(p, 2);
        t.seq     = ByteUtil.getBE32(p, 4) & 0xFFFFFFFFL;
        t.ack     = ByteUtil.getBE32(p, 8) & 0xFFFFFFFFL;
        t.hdrLen  = ((p[12] & 0xF0) >>> 4) * 4;
        t.flags   = p[13] & 0x3F;
        if (t.hdrLen < 20 || t.hdrLen > p.length) return null;
        t.data = Arrays.copyOfRange(p, t.hdrLen, p.length);
        return t;
    }

    // ── Checksums ──────────────────────────────────────────────────────────

    static int checksum16(byte[] data, int off, int len) {
        return (~foldCarry(checksum16Sum(data, off, len, 0))) & 0xFFFF;
    }

    private static int checksum16Sum(byte[] data, int off, int len, int seed) {
        int sum = seed;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) sum += (data[i] & 0xFF) << 8;
        return sum;
    }

    private static int foldCarry(int sum) {
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return sum;
    }
}
