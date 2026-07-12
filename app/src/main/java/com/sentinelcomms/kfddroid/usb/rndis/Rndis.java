/*
 * Copyright 2026 Charles Bricker
 * Licensed under the MIT License (see project root).
 */
package com.sentinelcomms.kfddroid.usb.rndis;

/** RNDIS wire protocol constants (Microsoft Remote NDIS spec). */
final class Rndis {

    private Rndis() {}

    // Message types
    static final int MSG_PACKET         = 0x00000001;
    static final int MSG_INITIALIZE     = 0x00000002;
    static final int MSG_INITIALIZE_C   = 0x80000002;
    static final int MSG_HALT           = 0x00000003;
    static final int MSG_QUERY          = 0x00000004;
    static final int MSG_QUERY_C        = 0x80000004;
    static final int MSG_SET            = 0x00000005;
    static final int MSG_SET_C          = 0x80000005;
    static final int MSG_KEEPALIVE      = 0x00000008;
    static final int MSG_KEEPALIVE_C    = 0x80000008;

    static final int STATUS_SUCCESS     = 0x00000000;

    // OIDs
    static final int OID_GEN_MAXIMUM_FRAME_SIZE      = 0x00010106;
    static final int OID_GEN_CURRENT_PACKET_FILTER   = 0x0001010E;
    static final int OID_802_3_PERMANENT_ADDRESS     = 0x01010101;
    static final int OID_802_3_CURRENT_ADDRESS       = 0x01010102;

    // Packet filter bits
    static final int PF_DIRECTED   = 0x00000001;
    static final int PF_MULTICAST  = 0x00000002;
    static final int PF_BROADCAST  = 0x00000008;

    // USB class-specific control requests on the RNDIS control interface
    static final int REQ_SEND_ENCAPSULATED = 0x00;
    static final int REQ_GET_ENCAPSULATED  = 0x01;

    static final int MAJOR_VERSION = 1;
    static final int MINOR_VERSION = 0;
    static final int MAX_TRANSFER_SIZE = 0x4000;
}
