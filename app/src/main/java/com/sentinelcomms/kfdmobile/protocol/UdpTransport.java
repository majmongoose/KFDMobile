/*
 * Copyright 2026 Charles Bricker
 * Licensed under the MIT License (see project root).
 */
package com.sentinelcomms.kfdmobile.protocol;

import java.io.IOException;

/**
 * Minimal request/response transport used by DataLinkIndependentProtocol.
 * Implemented by {@link UdpProtocol} (OS sockets) and by the userspace
 * RNDIS/IP/UDP stack (com.sentinelcomms.kfdmobile.usb.rndis.UsbRndisUdpTransport).
 */
public interface UdpTransport {
    /** Send {@code toRadio} and block until a reply arrives or timeout elapses. */
    byte[] txRx(byte[] toRadio) throws IOException;
}
