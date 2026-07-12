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
package com.sentinelcomms.kfddroid.p25.kmm;

import java.util.ArrayList;
import java.util.List;

/**
 * TIA 102.AACA Inventory Response — List Active Keys (inventory type 0xFD).
 * Body: [type(1)][marker(3)][count_hi(1)][count_lo(1)][keyInfo_0(6)]...[keyInfo_N(6)]
 * Mirrors KFDtool.P25.Kmm.InventoryResponseListActiveKeys.
 */
public class InventoryResponseListActiveKeys extends KmmBody {

    /** Inventory marker echoed from radio; 0 = no more pages, non-zero = send next request with this marker. */
    public int inventoryMarker = 0;

    public final List<KeyInfo> keys = new ArrayList<>();

    @Override public int getMessageId()    { return MessageId.InventoryResponse; }
    @Override public int getResponseKind() { return ResponseKind.NoResponse; }

    @Override
    public byte[] toBytes() { return new byte[0]; } // never sent

    @Override
    public void parse(byte[] contents) {
        // contents[0] = inventory type (0xFD) — verified by KmmFrame before calling parse
        // contents[1..3] = inventory marker (big-endian 3 bytes)
        // contents[4..5] = number of items (big-endian)
        // contents[6..] = 6 bytes per KeyInfo record
        if (contents.length < 6) return;
        inventoryMarker = ((contents[1] & 0xFF) << 16)
                        | ((contents[2] & 0xFF) <<  8)
                        |  (contents[3] & 0xFF);
        int count = ((contents[4] & 0xFF) << 8) | (contents[5] & 0xFF);
        for (int i = 0; i < count; i++) {
            int off = 6 + i * 6;
            if (off + 6 > contents.length) break;
            byte[] rec = new byte[6];
            System.arraycopy(contents, off, rec, 0, 6);
            KeyInfo ki = new KeyInfo();
            ki.parse(rec);
            keys.add(ki);
        }
    }
}
