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

/**
 * Motorola variant DLI session control message (MFID=0x90, version=0x01, msgId=0x31).
 * Body: [version(1)][opcode(1)][sourceDeviceType(1)][isSessionTypeIncluded(1)][sessionType(1)?]
 * Mirrors KFDtool.P25.Kmm.Mfid90SessionControlVer1.
 */
public class Mfid90SessionControlVer1 extends KmmBody {

    static final int VERSION = 0x01;

    public enum ScOpcode {
        Connect         ((byte) 0x01),
        ConnectAck      ((byte) 0x02),
        TransferDone    ((byte) 0x03),
        EndSession      ((byte) 0x04),
        EndSessionAck   ((byte) 0x05),
        Disconnect      ((byte) 0x06),
        DisconnectAck   ((byte) 0x07),
        BeginSession    ((byte) 0x08),
        BeginSessionAck ((byte) 0x09);

        public final byte value;
        ScOpcode(byte value) { this.value = value; }

        static ScOpcode fromByte(byte b) {
            for (ScOpcode op : values()) if (op.value == b) return op;
            return null;
        }
    }

    public enum ScSourceDeviceType {
        Kfd((byte) 0x01),
        Mr ((byte) 0x02),
        Kmf((byte) 0x03),
        Af ((byte) 0x04);

        public final byte value;
        ScSourceDeviceType(byte value) { this.value = value; }
    }

    public enum ScSessionType {
        KeyFill        ((byte) 0x01),
        BulkTransfer   ((byte) 0x02),
        StoreAndForward((byte) 0x03);

        public final byte value;
        ScSessionType(byte value) { this.value = value; }
    }

    public ScOpcode sessionControlOpcode;
    public ScSourceDeviceType sourceDeviceType = ScSourceDeviceType.Kfd;
    public boolean isSessionTypeIncluded = false;
    public ScSessionType sessionType;

    @Override public int getMessageId()    { return MessageId.SessionControl; }
    @Override public int getResponseKind() { return ResponseKind.Immediate; }

    @Override
    public byte[] toBytes() {
        List<Byte> out = new ArrayList<>();
        out.add((byte) VERSION);
        out.add(sessionControlOpcode.value);
        out.add(sourceDeviceType.value);
        out.add((byte) (isSessionTypeIncluded ? 1 : 0));
        if (isSessionTypeIncluded) out.add(sessionType.value);
        byte[] arr = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    @Override
    public void parse(byte[] contents) {
        if (contents.length < 4) return;
        // contents[0] = version — already verified by KmmFrame dispatcher
        ScOpcode op = ScOpcode.fromByte(contents[1]);
        if (op != null) sessionControlOpcode = op;
        // sourceDeviceType — decode
        for (ScSourceDeviceType t : ScSourceDeviceType.values()) {
            if (t.value == contents[2]) { sourceDeviceType = t; break; }
        }
        isSessionTypeIncluded = (contents[3] == 0x01);
        if (isSessionTypeIncluded && contents.length >= 5) {
            for (ScSessionType t : ScSessionType.values()) {
                if (t.value == contents[4]) { sessionType = t; break; }
            }
        }
    }
}
