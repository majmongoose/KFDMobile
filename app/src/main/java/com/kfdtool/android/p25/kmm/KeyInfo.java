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
package com.kfdtool.android.p25.kmm;

/**
 * Per-key record returned by InventoryResponseListActiveKeys.
 * Wire format: [ksetId(1)][sln_hi(1)][sln_lo(1)][algId(1)][keyId_hi(1)][keyId_lo(1)] = 6 bytes.
 * Mirrors KFDtool.P25.Kmm.KeyInfo.
 */
public class KeyInfo {
    public int keysetId;
    public int sln;
    public int algorithmId;
    public int keyId;

    public void parse(byte[] data) {
        if (data.length < 6) return;
        keysetId    =  data[0] & 0xFF;
        sln         = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        algorithmId =  data[3] & 0xFF;
        keyId       = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
    }

    public byte[] toBytes() {
        byte[] out = new byte[6];
        out[0] = (byte) keysetId;
        out[1] = (byte) ((sln >> 8) & 0xFF);
        out[2] = (byte) (sln & 0xFF);
        out[3] = (byte) algorithmId;
        out[4] = (byte) ((keyId >> 8) & 0xFF);
        out[5] = (byte) (keyId & 0xFF);
        return out;
    }
}
