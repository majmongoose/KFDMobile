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
package com.kfdtool.android.model;

import android.util.SparseArray;

/** Maps adapter model ID bytes to human-readable names, mirroring InteractTwiKfdtool.cs. */
public final class ModelId {

    private static final SparseArray<String> NAMES = new SparseArray<>();

    static {
        NAMES.put(0x00, "NOT SET");
        NAMES.put(0x01, "KFD100");
        NAMES.put(0x02, "KFDshield");
        NAMES.put(0x03, "KFDmicro");
        NAMES.put(0x04, "KFDpico");
        NAMES.put(0x05, "bblkey");
        NAMES.put(0x06, "KFDnano");
        NAMES.put(0x07, "RESERVED");
    }

    private ModelId() {}

    /** Returns a string in the form "NAME (0xXX)". */
    public static String format(int id) {
        String name = NAMES.get(id, "UNKNOWN");
        return name + String.format(" (0x%02X)", id);
    }
}
