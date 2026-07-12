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
package com.sentinelcomms.kfddroid.protocol;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * Minimal UDP send/receive wrapper used by DataLinkIndependentProtocol.
 *
 * When Android has multiple active network interfaces (WiFi/LTE and BT PAN
 * simultaneously), it may route packets via the default network even when the
 * destination is only reachable via a secondary interface.  To prevent this we
 * use ConnectivityManager to find the Network whose link addresses share a
 * subnet with the target, then call Network.bindSocket() before sending.
 * This is the Android-recommended API for forcing traffic through a specific
 * interface — confirmed to fix the BT PAN + WiFi/LTE coexistence problem.
 *
 * Mirrors UdpProtocol.cs from the .NET source.
 */
public class UdpProtocol implements UdpTransport {

    private static final int RECV_BUFFER = 4096;

    private final String  host;
    private final int     port;
    private final int     timeoutMs;
    private final Context context; // may be null; needed for ConnectivityManager

    public UdpProtocol(String host, int port, int timeoutMs, Context context) {
        this.host      = host;
        this.port      = port;
        this.timeoutMs = timeoutMs;
        this.context   = context;
    }

    public String  getHost()      { return host; }
    public int     getPort()      { return port; }
    public int     getTimeoutMs() { return timeoutMs; }

    /**
     * Send {@code toRadio} and block until a reply arrives or timeout elapses.
     *
     * @throws IOException on socket error or receive timeout
     */
    @Override
    public byte[] txRx(byte[] toRadio) throws IOException {
        InetAddress targetAddr = InetAddress.getByName(host);
        DatagramSocket socket  = new DatagramSocket();
        try {
            socket.setSoTimeout(timeoutMs);
            bindToCorrectNetwork(socket, targetAddr);
            socket.send(new DatagramPacket(toRadio, toRadio.length, targetAddr, port));
            byte[] buf  = new byte[RECV_BUFFER];
            DatagramPacket recv = new DatagramPacket(buf, buf.length);
            socket.receive(recv);
            return Arrays.copyOf(buf, recv.getLength());
        } finally {
            socket.close();
        }
    }

    // ── Network binding ───────────────────────────────────────────────────────

    /**
     * Use ConnectivityManager to locate the Network whose link addresses cover
     * the target IP, then bind the socket to it so Android routes via that
     * interface (e.g. BT PAN) rather than the default (WiFi/LTE).
     */
    private void bindToCorrectNetwork(DatagramSocket socket, InetAddress target) {
        if (!(target instanceof Inet4Address) || context == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            byte[] targetBytes = target.getAddress();

            for (Network network : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(network);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (!(la.getAddress() instanceof Inet4Address)) continue;
                    if (sameSubnet(targetBytes,
                                   la.getAddress().getAddress(),
                                   la.getPrefixLength())) {
                        // Found the network — bind the socket to force routing
                        network.bindSocket(socket);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
            // ConnectivityManager unavailable or bind failed — fall through
            // and let Android route however it sees fit
        }
    }

    /** True if {@code ip1} and {@code ip2} are on the same /{@code prefix} subnet. */
    private static boolean sameSubnet(byte[] ip1, byte[] ip2, int prefix) {
        if (prefix <= 0 || prefix > 32) return false;
        int mask = (prefix == 32) ? 0xFFFFFFFF : (0xFFFFFFFF << (32 - prefix));
        return (toInt(ip1) & mask) == (toInt(ip2) & mask);
    }

    private static int toInt(byte[] ip) {
        return ((ip[0] & 0xFF) << 24) | ((ip[1] & 0xFF) << 16)
             | ((ip[2] & 0xFF) <<  8) |  (ip[3] & 0xFF);
    }
}
