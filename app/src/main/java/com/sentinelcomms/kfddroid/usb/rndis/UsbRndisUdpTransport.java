/*
 * Copyright 2026 Charles Bricker
 * Licensed under the MIT License (see project root).
 */
package com.sentinelcomms.kfddroid.usb.rndis;

import android.util.Log;

import com.sentinelcomms.kfddroid.protocol.UdpTransport;

import java.io.IOException;
import java.util.Random;

/**
 * {@link UdpTransport} implementation that speaks the whole stack in userspace:
 * RNDIS over USB → Ethernet → IPv4 → UDP → DHCP-learned peer. Used when an APX
 * radio is plugged in and the OS doesn't provide an RNDIS driver.
 *
 * <p>Call {@link #bringUp()} once after construction. After that, {@link #txRx}
 * behaves like {@link com.sentinelcomms.kfddroid.protocol.UdpProtocol#txRx} but
 * targets the radio's DHCP-assigned server IP.
 *
 * <p>Not thread-safe: DLI serializes calls anyway.
 */
public class UsbRndisUdpTransport implements UdpTransport {

    private static final String TAG = "UsbRndisUdp";
    private static final int    RX_LOOP_TIMEOUT_MS = 500;

    private final UsbRndisManager mgr;
    private final RndisSession    rndis;
    private volatile int          targetPort;
    private final int             overallTimeoutMs;

    private byte[] myMac;
    private byte[] myIp;
    private byte[] radioMac;
    private byte[] radioIp;

    private final int myPort;
    private final Random ipIdRand = new Random();

    public UsbRndisUdpTransport(UsbRndisManager mgr, int targetPort, int overallTimeoutMs) {
        this.mgr             = mgr;
        this.rndis           = new RndisSession(mgr);
        this.targetPort      = targetPort;
        this.overallTimeoutMs = overallTimeoutMs;
        // Ephemeral source port; keeps constant so all round-trips look like one flow
        this.myPort = 40000 + (new Random().nextInt(20000));
    }

    /**
     * Runs RNDIS INIT + DHCP. Blocking; call from a background thread.
     * After this returns, {@link #getRadioIp()} is populated.
     */
    public void bringUp() throws IOException {
        rndis.initialize();
        rndis.enableDefaultPacketFilter();
        myMac = rndis.queryPermanentMac();

        DhcpClient dhcp = new DhcpClient(rndis, myMac);
        DhcpClient.Lease lease = dhcp.obtainLease();
        this.myIp     = lease.clientIp;
        this.radioIp  = lease.serverIp;
        this.radioMac = lease.serverMac; // src of the ACK Ethernet frame
    }

    /** IP that DHCP told us belongs to the radio (used as DLI host). */
    public String getRadioIp() { return radioIp == null ? null : ByteUtil.ipToStr(radioIp); }

    /** Update the destination UDP port (e.g. after user reconfigures DLI). */
    public void setTargetPort(int port) { this.targetPort = port; }

    @Override
    public byte[] txRx(byte[] toRadio) throws IOException {
        if (myIp == null || radioMac == null) throw new IOException("USB DLI not up");

        int ipId = ipIdRand.nextInt() & 0xFFFF;
        byte[] udp = NetProto.buildUdp(myIp, radioIp, myPort, targetPort, toRadio);
        byte[] ip  = NetProto.buildIpv4(myIp, radioIp, NetProto.IP_PROTO_UDP, udp, ipId);
        byte[] eth = NetProto.buildEthernet(radioMac, myMac, NetProto.ETHERTYPE_IPV4, ip);
        rndis.sendPacket(eth);

        long deadline = System.currentTimeMillis() + overallTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame;
            try {
                frame = rndis.receivePacket(RX_LOOP_TIMEOUT_MS);
            } catch (IOException e) {
                // Typically a bulkIn timeout — keep waiting until overall deadline
                continue;
            }
            if (frame == null) continue;

            NetProto.Eth ef = NetProto.parseEthernet(frame);
            if (ef == null) continue;

            if (ef.etherType == NetProto.ETHERTYPE_ARP) {
                handleArp(ef);
                continue;
            }
            if (ef.etherType != NetProto.ETHERTYPE_IPV4) continue;

            NetProto.Ip pip = NetProto.parseIpv4(ef.payload);
            if (pip == null || pip.protocol != NetProto.IP_PROTO_UDP) continue;

            NetProto.Udp pudp = NetProto.parseUdp(pip.payload);
            if (pudp == null) continue;
            if (pudp.dstPort != myPort) continue;
            // srcPort should match targetPort, but some devices reply from ephemeral —
            // treat any UDP to our port from the radio as the reply.
            return pudp.data;
        }
        throw new IOException("USB DLI receive timeout");
    }

    // ── One-shot TCP client ────────────────────────────────────────────────

    /**
     * Open a TCP connection to the radio on {@code dstPort}, send {@code payload},
     * read up to {@code overallTimeoutMs} for a response, then RST. Returns the
     * accumulated response bytes.
     *
     * <p>Sequence:
     * <pre>
     *   SYN                  → learn peer ISN from SYN-ACK
     *   ACK+PSH data         → send payload piggybacked on connection-complete ACK
     *   (loop)  read segs    → accumulate response until PSH or FIN
     *   RST                  → abort tear-down (no polite FIN dance)
     * </pre>
     * Not a general-purpose TCP; adequate for the tiny radio diagnostic queries
     * used by the model check.
     */
    public byte[] oneShotTcp(int dstPort, byte[] payload, int totalTimeoutMs) throws IOException {
        if (myIp == null || radioMac == null) throw new IOException("USB DLI not up");

        int srcPort = 45000 + (new Random().nextInt(15000));
        long mySeq  = new Random().nextInt() & 0xFFFFFFFFL;
        long peerSeq;

        // 1. SYN with MSS=1460 option
        byte[] mss = new byte[] { 0x02, 0x04, 0x05, (byte) 0xB4 };
        sendTcp(srcPort, dstPort, mySeq, 0, NetProto.TCP_SYN, mss, new byte[0]);

        NetProto.Tcp synAck = awaitTcp(srcPort,
                NetProto.TCP_SYN | NetProto.TCP_ACK, totalTimeoutMs);

        peerSeq = (synAck.seq + 1) & 0xFFFFFFFFL;
        mySeq   = (mySeq + 1) & 0xFFFFFFFFL;

        // 2a. Bare ACK completing the 3-way handshake — some peers only pass data
        //     up to the app after the connection is fully established, and dislike
        //     data piggybacked on the connection-completing ACK.
        sendTcp(srcPort, dstPort, mySeq, peerSeq, NetProto.TCP_ACK, null, new byte[0]);

        // 2b. Payload with PSH+ACK in its own segment.
        sendTcp(srcPort, dstPort, mySeq, peerSeq,
                NetProto.TCP_ACK | NetProto.TCP_PSH, null, payload);
        long sentEnd = (mySeq + payload.length) & 0xFFFFFFFFL;

        // 3. Read response segments; accumulate until quiet
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            NetProto.Tcp seg;
            try {
                seg = awaitTcp(srcPort, /* anyFlags */ -1,
                        (int) Math.max(200, deadline - System.currentTimeMillis()));
            } catch (IOException timeout) {
                break;
            }

            if ((seg.flags & NetProto.TCP_RST) != 0) {
                Log.w(TAG, "TCP: peer sent RST");
                break;
            }
            if (seg.data.length > 0) {
                out.write(seg.data, 0, seg.data.length);
                peerSeq = (seg.seq + seg.data.length) & 0xFFFFFFFFL;
                sendTcp(srcPort, dstPort, sentEnd, peerSeq, NetProto.TCP_ACK, null, new byte[0]);
            }
            if ((seg.flags & NetProto.TCP_FIN) != 0) {
                peerSeq = (peerSeq + 1) & 0xFFFFFFFFL;
                sendTcp(srcPort, dstPort, sentEnd, peerSeq, NetProto.TCP_ACK, null, new byte[0]);
                break;
            }
            // PSH on a data segment = complete application message delivered; we're done.
            if (seg.data.length > 0 && (seg.flags & NetProto.TCP_PSH) != 0) break;
            // Or: we got something and this was an ACK-only follow-up.
            if (out.size() > 0 && seg.data.length == 0) break;
        }

        // 4. RST — cheap teardown
        sendTcp(srcPort, dstPort, sentEnd, peerSeq, NetProto.TCP_RST | NetProto.TCP_ACK, null, new byte[0]);
        Log.i(TAG, "TCP oneShot to :" + dstPort + " -> " + out.size() + " bytes");
        return out.toByteArray();
    }

    private void sendTcp(int srcPort, int dstPort, long seq, long ack, int flags,
                         byte[] options, byte[] data) throws IOException {
        int ipId = ipIdRand.nextInt() & 0xFFFF;
        byte[] tcp = NetProto.buildTcp(myIp, radioIp, srcPort, dstPort,
                seq, ack, flags, 8192, options, data);
        byte[] ip  = NetProto.buildIpv4(myIp, radioIp, NetProto.IP_PROTO_TCP, tcp, ipId);
        byte[] eth = NetProto.buildEthernet(radioMac, myMac, NetProto.ETHERTYPE_IPV4, ip);
        rndis.sendPacket(eth);
    }

    /**
     * Read frames from RNDIS until a TCP segment addressed to {@code srcPort} arrives
     * with matching flags (or any if {@code wantedFlags == -1}). Handles ARP inline.
     */
    private NetProto.Tcp awaitTcp(int srcPort, int wantedFlags, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame;
            try {
                frame = rndis.receivePacket(RX_LOOP_TIMEOUT_MS);
            } catch (IOException e) { continue; }
            if (frame == null) continue;
            NetProto.Eth ef = NetProto.parseEthernet(frame);
            if (ef == null) continue;
            if (ef.etherType == NetProto.ETHERTYPE_ARP) { handleArp(ef); continue; }
            if (ef.etherType != NetProto.ETHERTYPE_IPV4) continue;
            NetProto.Ip pip = NetProto.parseIpv4(ef.payload);
            if (pip == null || pip.protocol != NetProto.IP_PROTO_TCP) continue;
            NetProto.Tcp t = NetProto.parseTcp(pip.payload);
            if (t == null || t.dstPort != srcPort) continue;
            if (wantedFlags >= 0 && (t.flags & wantedFlags) != wantedFlags) continue;
            return t;
        }
        throw new IOException("TCP wait timed out");
    }

    /** Reply to gratuitous ARP requests for our IP so the radio's cache stays warm. */
    private void handleArp(NetProto.Eth ef) throws IOException {
        NetProto.Arp arp = NetProto.parseArp(ef.payload);
        if (arp == null || arp.opcode != NetProto.ARP_OP_REQUEST) return;
        if (!ipEquals(arp.targetIp, myIp)) return;
        byte[] reply = NetProto.buildArp(NetProto.ARP_OP_REPLY, myMac, myIp,
                                         arp.senderMac, arp.senderIp);
        byte[] frame = NetProto.buildEthernet(arp.senderMac, myMac, NetProto.ETHERTYPE_ARP, reply);
        rndis.sendPacket(frame);
    }

    private static boolean ipEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != 4 || b.length != 4) return false;
        for (int i = 0; i < 4; i++) if (a[i] != b[i]) return false;
        return true;
    }

    @Override
    public void close() {
        // The transport does not own the UsbRndisManager — MainActivity does.
    }
}
