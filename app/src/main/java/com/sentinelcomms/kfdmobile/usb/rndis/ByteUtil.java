/*
 * Copyright 2026 Charles Bricker
 * Licensed under the MIT License (see project root).
 */
package com.sentinelcomms.kfdmobile.usb.rndis;

/** Little-endian and big-endian byte helpers shared by RNDIS + IP layers. */
final class ByteUtil {

    private ByteUtil() {}

    static void putLE32(byte[] b, int off, int v) {
        b[off]     = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    static int getLE32(byte[] b, int off) {
        return  (b[off] & 0xFF)
             | ((b[off + 1] & 0xFF) << 8)
             | ((b[off + 2] & 0xFF) << 16)
             | ((b[off + 3] & 0xFF) << 24);
    }

    static void putBE16(byte[] b, int off, int v) {
        b[off]     = (byte) ((v >>> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    static int getBE16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    static void putBE32(byte[] b, int off, int v) {
        b[off]     = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    static int getBE32(byte[] b, int off) {
        return  ((b[off] & 0xFF) << 24)
             |  ((b[off + 1] & 0xFF) << 16)
             |  ((b[off + 2] & 0xFF) << 8)
             |   (b[off + 3] & 0xFF);
    }

    static String macToStr(byte[] mac) {
        return String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    }

    static String ipToStr(byte[] ip) {
        return String.format("%d.%d.%d.%d",
                ip[0] & 0xFF, ip[1] & 0xFF, ip[2] & 0xFF, ip[3] & 0xFF);
    }
}
