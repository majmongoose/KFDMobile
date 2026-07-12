/*
 * Ported from KFDtool.P25.Validator.FieldValidator (MIT License).
 * Copyright 2019-2020 KFDtool, LLC / Natalie Moore / Ilya Smirnov
 * Copyright 2026 Charles Bricker
 */
package com.sentinelcomms.kfddroid.model;

/**
 * DES key material validation — parity only (matches C# FieldValidator.IsValidSingleDesKeyParity).
 * The lowest bit of every byte must make the byte odd-parity.
 */
public final class DesKeyValidator {

    private DesKeyValidator() {}

    /** True if every byte of {@code key} has correct odd parity. */
    public static boolean isValidParity(byte[] key) {
        if (key == null || key.length != 8) return false;
        for (byte b : key) {
            int u = b & 0xFF;
            boolean parityBit = (u & 0x01) != 0;
            int c = u & 0xFE;
            int t1 = (c & 0x0F) ^ (c >> 4);
            int t2 = (t1 & 0x03) ^ (t1 >> 2);
            int t3 = (t2 & 0x01) ^ (t2 >> 1);
            boolean calcParity = (t3 == 0);
            if (parityBit != calcParity) return false;
        }
        return true;
    }

    /** True if this algorithm ID uses DES key material (parity applies). */
    public static boolean isDesAlgorithm(int algId) {
        return algId == AlgorithmId.DES_OFB || algId == AlgorithmId.DES_XL;
    }

    /**
     * Rewrite the parity bit (LSB) of every byte in {@code key} so the byte has
     * odd parity. Modifies {@code key} in place. Use after generating a random
     * 8-byte DES key so it passes {@link #isValidParity}.
     */
    public static void fixParity(byte[] key) {
        if (key == null) return;
        for (int i = 0; i < key.length; i++) {
            int u = key[i] & 0xFE;
            int t1 = (u & 0x0F) ^ (u >> 4);
            int t2 = (t1 & 0x03) ^ (t1 >> 2);
            int t3 = (t2 & 0x01) ^ (t2 >> 1);
            // t3 == 0 → even count of 1-bits in high 7 bits → need parity bit = 1 for odd
            key[i] = (byte) (u | ((t3 == 0) ? 0x01 : 0x00));
        }
    }
}
