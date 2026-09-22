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
package com.sentinelcomms.kfdmobile.p25.kmm;

/**
 * TIA 102.AACA Inventory Command — List Active Keys (inventory type 0xFD).
 * Body: [type(1)][marker_b2(1)][marker_b1(1)][marker_b0(1)][maxKeys_hi(1)][maxKeys_lo(1)]
 * Mirrors KFDtool.P25.Kmm.InventoryCommandListActiveKeys.
 */
public class InventoryCommandListActiveKeys extends KmmBody {

    /** Inventory type byte for ListActiveKeys (TIA 102 InventoryType enum = 0xFD). */
    static final int INVENTORY_TYPE = 0xFD;

    /** Starting marker; 0 = first page. Radio echoes a non-zero marker if more pages follow. */
    public int inventoryMarker  = 0;

    /** Max keys per response — 78 for TWI (TIA 102.AACD-A 3.9.2.11). */
    public int maxKeysRequested = 78;

    @Override public int getMessageId()    { return MessageId.InventoryCommand; }
    @Override public int getResponseKind() { return ResponseKind.Immediate; }

    @Override
    public byte[] toBytes() {
        byte[] out = new byte[6];
        out[0] = (byte) INVENTORY_TYPE;
        out[1] = (byte) ((inventoryMarker >> 16) & 0xFF);
        out[2] = (byte) ((inventoryMarker >>  8) & 0xFF);
        out[3] = (byte)  (inventoryMarker         & 0xFF);
        out[4] = (byte) ((maxKeysRequested >> 8)  & 0xFF);
        out[5] = (byte)  (maxKeysRequested         & 0xFF);
        return out;
    }

    @Override
    public void parse(byte[] contents) {
        // never received as a command
    }
}
