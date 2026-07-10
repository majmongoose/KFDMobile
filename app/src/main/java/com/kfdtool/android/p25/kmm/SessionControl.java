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
 * Standard DLI session control message (MFID=0x00, version=0x00, msgId=0x31).
 * Body: [version(1)][opcode(1)][sourceDeviceType(1)]
 * Mirrors KFDtool.P25.Kmm.SessionControl.
 */
public class SessionControl extends KmmBody {

    static final int VERSION = 0x00;

    public enum ScOpcode {
        ReadyRequest    ((byte) 0x01),
        ReadyGeneralMode((byte) 0x02),
        TransferDone    ((byte) 0x03),
        EndSession      ((byte) 0x04),
        EndSessionAck   ((byte) 0x05),
        Disconnect      ((byte) 0x06),
        DisconnectAck   ((byte) 0x07);

        public final byte value;
        ScOpcode(byte value) { this.value = value; }

        static ScOpcode fromByte(byte b) {
            for (ScOpcode op : values()) if (op.value == b) return op;
            return null;
        }
    }

    public enum ScSourceDeviceType {
        Kfd((byte) 0x01),
        Mr ((byte) 0x02);

        public final byte value;
        ScSourceDeviceType(byte value) { this.value = value; }
    }

    public ScOpcode sessionControlOpcode;
    public ScSourceDeviceType sourceDeviceType = ScSourceDeviceType.Kfd;

    @Override public int getMessageId()    { return MessageId.SessionControl; }
    @Override public int getResponseKind() { return ResponseKind.Immediate; }

    @Override
    public byte[] toBytes() {
        return new byte[]{
            (byte) VERSION,
            sessionControlOpcode.value,
            sourceDeviceType.value
        };
    }

    @Override
    public void parse(byte[] contents) {
        if (contents.length < 3) return;
        // contents[0] = version — already verified by KmmFrame dispatcher
        ScOpcode op = ScOpcode.fromByte(contents[1]);
        if (op != null) sessionControlOpcode = op;
        sourceDeviceType = (contents[2] == 0x01) ? ScSourceDeviceType.Kfd : ScSourceDeviceType.Mr;
    }
}
