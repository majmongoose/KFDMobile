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

/** A single key entry within a ModifyKeyCommand. */
public class KeyItem {
    public boolean erase;
    public boolean kek;
    public int sln;     // Subscriber Line Number / CKR
    public int keyId;
    public byte[] key;

    public byte[] toBytes() {
        List<Byte> out = new ArrayList<>();
        // key format byte: bit 7 = KEK, bit 5 = Erase (matches .NET KeyItem.ToBytes)
        int fmt = 0;
        if (kek)   fmt |= 0x80;
        if (erase) fmt |= 0x20;
        out.add((byte) fmt);
        // SLN (2 bytes big-endian)
        out.add((byte) ((sln >> 8) & 0xFF));
        out.add((byte) (sln & 0xFF));
        // Key ID (2 bytes big-endian)
        out.add((byte) ((keyId >> 8) & 0xFF));
        out.add((byte) (keyId & 0xFF));
        // Key material
        for (byte b : key) out.add(b);
        byte[] arr = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    public void parse(byte[] data, int keyLength) {
        erase  = (data[0] & 0xFF) == 0x01;
        sln    = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        keyId  = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
        key = new byte[keyLength];
        System.arraycopy(data, 5, key, 0, keyLength);
    }
}
