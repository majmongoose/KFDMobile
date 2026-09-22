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

import java.util.ArrayList;
import java.util.List;

/** TIA 102.AACA Inventory Response — List Active Keyset IDs (inventory type 0x02). */
public class InventoryResponseListActiveKsetIds extends KmmBody {

    public final List<Integer> ksetIds = new ArrayList<>();

    @Override
    public int getMessageId() { return MessageId.InventoryResponse; }

    @Override
    public int getResponseKind() { return ResponseKind.NoResponse; }

    @Override
    public byte[] toBytes() {
        // Format: [type(1)][count_hi(1)][count_lo(1)][ksetId_1(1)...]
        byte[] out = new byte[3 + ksetIds.size()];
        out[0] = 0x02; // inventory type = ListActiveKsetIds
        out[1] = (byte)((ksetIds.size() >> 8) & 0xFF);
        out[2] = (byte)(ksetIds.size() & 0xFF);
        for (int i = 0; i < ksetIds.size(); i++) out[3 + i] = (byte)(ksetIds.get(i) & 0xFF);
        return out;
    }

    @Override
    public void parse(byte[] contents) {
        // contents[0] = inventory type (0x02 = ListActiveKsetIds)
        // contents[1..2] = number of items (big-endian)
        // contents[3..] = one byte per keyset ID
        if (contents.length < 3) return;
        int count = ((contents[1] & 0xFF) << 8) | (contents[2] & 0xFF);
        for (int i = 0; i < count && (3 + i) < contents.length; i++) {
            ksetIds.add(contents[3 + i] & 0xFF);
        }
    }
}
