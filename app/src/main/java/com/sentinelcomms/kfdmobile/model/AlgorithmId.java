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
package com.sentinelcomms.kfdmobile.model;

/** P25 algorithm IDs per TIA-102.BAAC-D 2.8. */
public final class AlgorithmId {
    public static final int ACCORDION  = 0x00;
    public static final int BATON_ODD  = 0x01;
    public static final int FIREFLY    = 0x02;
    public static final int MAYFLY     = 0x03;
    public static final int SAVILLE    = 0x04;
    public static final int PADSTONE   = 0x05;
    public static final int BATON_EVEN = 0x41;
    public static final int CLEAR      = 0x80;
    public static final int DES_OFB    = 0x81;
    public static final int TDES       = 0x83;
    public static final int AES_256    = 0x84;
    public static final int AES_128    = 0x85;
    public static final int DES_XL     = 0x9F;
    public static final int DVP_XL     = 0xA0;
    public static final int ADP        = 0xAA;
    public static final int ILYA       = 0x69;

    private AlgorithmId() {}

    /** Sentinel value for the "Other" spinner entry — user supplies the raw ALGID. */
    public static final int OTHER = -1;

    /** Common algorithms shown in the spinner. "Other" lets the user enter a raw hex ALGID. */
    public static final String[] DISPLAY_NAMES = {
        "ADP/ARC4 (0xAA)",
        "AES-256 (0x84)",
        "DES-OFB (0x81)",
        "DES-XL (0x9F)",
        "Other",
    };

    public static final int[] VALUES = {
        ADP, AES_256, DES_OFB, DES_XL, OTHER
    };

    /** Key length in bytes for a given algorithm, or -1 if variable/unknown. */
    public static int keyLength(int algId) {
        switch (algId) {
            case AES_256:   return 32;
            case AES_128:   return 16;
            case TDES:      return 24;
            case DES_OFB:
            case DES_XL:    return 8;
            case ADP:       return 5;
            case CLEAR:     return 0;
            default:        return -1;
        }
    }
}
