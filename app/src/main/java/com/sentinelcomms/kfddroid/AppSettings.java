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
package com.sentinelcomms.kfddroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;

/** Centralizes display/parse settings for Key ID and SLN/CKR number formats. */
public class AppSettings {

    public static final String PREF_FILE      = "kfd_settings";
    public static final String KEY_ID_HEX     = "key_id_hex";
    public static final String SLN_HEX        = "sln_hex";

    private final SharedPreferences prefs;

    public AppSettings(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    // ── Getters / setters ─────────────────────────────────────────────────────

    public boolean isKeyIdHex() { return prefs.getBoolean(KEY_ID_HEX, false); }
    public boolean isSlnHex()   { return prefs.getBoolean(SLN_HEX,    false); }

    public void setKeyIdHex(boolean v) { prefs.edit().putBoolean(KEY_ID_HEX, v).apply(); }
    public void setSlnHex(boolean v)   { prefs.edit().putBoolean(SLN_HEX,    v).apply(); }

    /** Raw prefs object for registering change listeners. */
    public SharedPreferences getPrefs() { return prefs; }

    // ── Display formatters ────────────────────────────────────────────────────

    public String formatKeyId(int id)  { return isKeyIdHex() ? String.format("0x%04X", id) : String.valueOf(id); }
    public String formatSln(int sln)   { return isSlnHex()   ? String.format("0x%04X", sln) : String.valueOf(sln); }

    // ── Field hints ───────────────────────────────────────────────────────────

    public String keyIdHint() { return isKeyIdHex() ? "Key ID (0x0000–0xFFFF)" : "Key ID (0–65535)"; }
    public String slnHint()   { return isSlnHex()   ? "SLN/CKR (0x0000–0xFFFF)" : "SLN/CKR (0–65535)"; }

    // ── Input types ───────────────────────────────────────────────────────────

    public int keyIdInputType() { return isKeyIdHex() ? hexInputType() : InputType.TYPE_CLASS_NUMBER; }
    public int slnInputType()   { return isSlnHex()   ? hexInputType() : InputType.TYPE_CLASS_NUMBER; }

    private static int hexInputType() {
        return InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
    }

    // ── Parsers (lenient: always accept 0x prefix; hex mode accepts bare hex) ─

    public int parseKeyId(String s) { return parseHexOrDec(s.trim(), isKeyIdHex()); }
    public int parseSln(String s)   { return parseHexOrDec(s.trim(), isSlnHex()); }

    public static int parseHexOrDec(String s, boolean hexMode) {
        if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16);
        return Integer.parseInt(s, hexMode ? 16 : 10);
    }

    // ── Validation error strings ──────────────────────────────────────────────

    public String keyIdRangeError() { return isKeyIdHex() ? "Must be 0x0000–0xFFFF" : "Must be 0–65535"; }
    public String slnRangeError()   { return isSlnHex()   ? "Must be 0x0000–0xFFFF" : "Must be 0–65535"; }
    public String keyIdInvalidError() { return isKeyIdHex() ? "Invalid hex" : "Invalid number"; }
    public String slnInvalidError()   { return isSlnHex()   ? "Invalid hex" : "Invalid number"; }
}
