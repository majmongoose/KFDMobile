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
package com.sentinelcomms.kfddroid.p25;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Transport-agnostic P25 device protocol interface.
 * Implemented by ThreeWireProtocol (TWI/USB) and DataLinkIndependentProtocol (DLI/UDP).
 * Mirrors IDeviceProtocol from the .NET source.
 */
public interface DeviceProtocol {

    /** Send the KFD key signature to the remote device (no-op for DLI). */
    void sendKeySignature() throws IOException, InterruptedException;

    /** Establish a session and return the remote device type. */
    DeviceType initSession() throws IOException, InterruptedException;

    /** Cleanly close the session. */
    void endSession() throws IOException, InterruptedException;

    /**
     * Exchange one KMM transfer.
     * @param toRadio serialized KMM frame bytes (with preamble if applicable)
     * @return raw response bytes (with preamble if applicable)
     */
    byte[] performKmmTransfer(byte[] toRadio) throws IOException, InterruptedException;

    /**
     * Whether the most recent performKmmTransfer response included a 14-byte preamble.
     * Always true for DLI; per-response for TWI (Motorola 0xC3 opcode).
     */
    boolean lastResponseHadPreamble();

    /** Flush any pending receive data (no-op for DLI). */
    void clearQueues();

    /** Attach a low-level debug log listener (null to remove). */
    void setDebugListener(Consumer<String> listener);
}
