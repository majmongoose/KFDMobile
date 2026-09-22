/*
 * Ported from KFDtool.P25.Kmm.InventoryResponseListRsiItems (MIT License).
 * Copyright 2019-2020 KFDtool, LLC / Natalie Moore / Ilya Smirnov
 * Copyright 2026 Charles Bricker
 */
package com.sentinelcomms.kfdmobile.p25.kmm;

import java.util.ArrayList;
import java.util.List;

/**
 * TIA 102.AACA Inventory Response — List RSI Items (inventory type 0x0B).
 * Used by MR emulator to respond to {@code InventoryCommandListRsiItems} queries
 * with a single factory-default RSI item (RSI=1, MessageNumber=0), which is what
 * a fresh Motorola SU would report and what the KVL5000 requires before it will
 * send keyload commands.
 */
public class InventoryResponseListRsiItems extends KmmBody {

    public static final int INVENTORY_TYPE = 0x0B;

    /** Single RSI entry: 24-bit RSI + 16-bit message number. */
    public static class RsiItem {
        public int rsi;             // 24-bit
        public int messageNumber;   // 16-bit
        public RsiItem(int rsi, int messageNumber) {
            this.rsi = rsi;
            this.messageNumber = messageNumber;
        }
    }

    public final List<RsiItem> rsiItems = new ArrayList<>();

    @Override
    public int getMessageId() { return MessageId.InventoryResponse; }

    @Override
    public int getResponseKind() { return ResponseKind.NoResponse; }

    @Override
    public byte[] toBytes() {
        // Format: [type(1)][count_hi(1)][count_lo(1)][item_1(5) ...]
        // Each item is RSI(24-bit big-endian) + MessageNumber(16-bit big-endian).
        byte[] out = new byte[3 + rsiItems.size() * 5];
        out[0] = (byte) INVENTORY_TYPE;
        out[1] = (byte) ((rsiItems.size() >> 8) & 0xFF);
        out[2] = (byte)  (rsiItems.size() & 0xFF);
        int off = 3;
        for (RsiItem item : rsiItems) {
            out[off++] = (byte) ((item.rsi >> 16) & 0xFF);
            out[off++] = (byte) ((item.rsi >> 8)  & 0xFF);
            out[off++] = (byte)  (item.rsi        & 0xFF);
            out[off++] = (byte) ((item.messageNumber >> 8) & 0xFF);
            out[off++] = (byte)  (item.messageNumber       & 0xFF);
        }
        return out;
    }

    @Override
    public void parse(byte[] contents) {
        if (contents.length < 3 || (contents[0] & 0xFF) != INVENTORY_TYPE) return;
        int count = ((contents[1] & 0xFF) << 8) | (contents[2] & 0xFF);
        int off = 3;
        for (int i = 0; i < count && off + 5 <= contents.length; i++) {
            int rsi = ((contents[off] & 0xFF) << 16)
                    | ((contents[off + 1] & 0xFF) << 8)
                    |  (contents[off + 2] & 0xFF);
            int msg = ((contents[off + 3] & 0xFF) << 8) | (contents[off + 4] & 0xFF);
            rsiItems.add(new RsiItem(rsi, msg));
            off += 5;
        }
    }
}
