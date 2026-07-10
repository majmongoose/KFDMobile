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

import java.util.ArrayList;
import java.util.List;

/** TIA 102 Rekey Acknowledgment — response to ModifyKeyCommand. */
public class RekeyAcknowledgment extends KmmBody {

    public int messageIdAcknowledged;
    public int numberOfItems;
    public final List<KeyStatus> keys = new ArrayList<>();

    @Override
    public int getMessageId() { return MessageId.RekeyAcknowledgment; }

    @Override
    public int getResponseKind() { return ResponseKind.NoResponse; }

    @Override
    public byte[] toBytes() {
        List<Byte> out = new ArrayList<>();
        out.add((byte) messageIdAcknowledged);
        out.add((byte) numberOfItems);
        for (KeyStatus ks : keys) {
            out.add((byte) ks.algorithmId);
            out.add((byte)((ks.keyId >> 8) & 0xFF));
            out.add((byte)(ks.keyId & 0xFF));
            out.add((byte) ks.status);
        }
        byte[] arr = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    @Override
    public void parse(byte[] contents) {
        if (contents.length < 2) return;
        messageIdAcknowledged = contents[0] & 0xFF;
        numberOfItems         = contents[1] & 0xFF;
        int offset = 2;
        for (int i = 0; i < numberOfItems && offset + 3 < contents.length; i++) {
            KeyStatus ks = new KeyStatus();
            ks.algorithmId = contents[offset]     & 0xFF;
            ks.keyId = ((contents[offset + 1] & 0xFF) << 8) | (contents[offset + 2] & 0xFF);
            ks.status      = contents[offset + 3] & 0xFF;
            keys.add(ks);
            offset += 4;
        }
    }
}
