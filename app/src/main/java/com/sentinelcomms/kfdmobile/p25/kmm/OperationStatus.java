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

public final class OperationStatus {
    public static final int CommandWasPerformed        = 0x00;
    public static final int InvalidMessageId           = 0x01;
    public static final int ProtocolVersionMismatch    = 0x02;
    public static final int AuthenticationFailed       = 0x03;
    public static final int KeyAlgorithmUnsupported    = 0x04;
    public static final int KeyNotFound                = 0x05;
    public static final int MemoryFull                 = 0x06;
    public static final int InvalidKeysetId            = 0x07;
    public static final int InvalidKeyId               = 0x08;
    public static final int InvalidSln                 = 0x09;
    public static final int InvalidKeyLength           = 0x0A;
    public static final int CommandWasNotPerformed     = 0xFF;

    private OperationStatus() {}

    public static String toStatusString(int status) {
        switch (status) {
            case CommandWasPerformed: return "Command was performed";
            case InvalidMessageId:   return "Invalid message ID";
            case ProtocolVersionMismatch: return "Protocol version mismatch";
            case AuthenticationFailed: return "Authentication failed";
            case KeyAlgorithmUnsupported: return "Key algorithm unsupported";
            case KeyNotFound:         return "Key not found";
            case MemoryFull:          return "Memory full";
            case InvalidKeysetId:     return "Invalid keyset ID";
            case InvalidKeyId:        return "Invalid key ID";
            case InvalidSln:          return "Invalid SLN";
            case InvalidKeyLength:    return "Invalid key length";
            case CommandWasNotPerformed: return "Command was not performed";
            default: return String.format("Unknown (0x%02X)", status);
        }
    }
}
