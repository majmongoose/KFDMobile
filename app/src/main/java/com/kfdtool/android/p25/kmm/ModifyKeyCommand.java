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

/** TIA 102.AACA-A 10.2.19 — Modify Key Command. */
public class ModifyKeyCommand extends KmmBody {

    public int keysetId;
    public int algorithmId;
    public final List<KeyItem> keyItems = new ArrayList<>();

    @Override
    public int getMessageId() { return MessageId.ModifyKeyCommand; }

    @Override
    public int getResponseKind() { return ResponseKind.Immediate; }

    @Override
    public byte[] toBytes() {
        List<Byte> keys = new ArrayList<>();
        for (KeyItem ki : keyItems) {
            for (byte b : ki.toBytes()) keys.add(b);
        }

        List<Byte> contents = new ArrayList<>();

        // decryption instruction format (no MI, clear text = 0x80 algid)
        contents.add((byte) 0x00); // no MI flag
        contents.add((byte) 0x00); // extended format
        contents.add((byte) 0x80); // algid = CLEAR (no KEK)
        contents.add((byte) 0x00); // key id hi
        contents.add((byte) 0x00); // key id lo
        contents.add((byte) keysetId);
        contents.add((byte) algorithmId);
        contents.add((byte) (keyItems.isEmpty() ? 0 : keyItems.get(0).key.length));
        contents.add((byte) keyItems.size());
        contents.addAll(keys);

        byte[] arr = new byte[contents.size()];
        for (int i = 0; i < contents.size(); i++) arr[i] = contents.get(i);
        return arr;
    }

    @Override
    public void parse(byte[] contents) {
        if (contents.length < 9) throw new IllegalArgumentException("ModifyKeyCommand too short");
        keysetId    = contents[5] & 0xFF;
        algorithmId = contents[6] & 0xFF;
        int keyLength = contents[7] & 0xFF;
        int keyCount  = contents[8] & 0xFF;
        for (int i = 0; i < keyCount; i++) {
            byte[] item = new byte[5 + keyLength];
            System.arraycopy(contents, 9 + i * (5 + keyLength), item, 0, 5 + keyLength);
            KeyItem ki = new KeyItem();
            ki.parse(item, keyLength);
            keyItems.add(ki);
        }
    }
}
