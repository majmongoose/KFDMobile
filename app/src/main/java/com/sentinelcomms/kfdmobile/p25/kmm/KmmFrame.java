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

import java.util.Locale;
import java.io.IOException;

/**
 * Serializes / deserializes a KMM (Key Management Message) frame.
 *
 * Wire format (no preamble, used over three-wire):
 *   [0]     message ID
 *   [1..2]  message length (big-endian)  = 7 + bodyLen
 *   [3]     message format (response kind bits 7:6)
 *   [4..6]  destination RSI  (0xFF FF FF = broadcast)
 *   [7..9]  source RSI       (0xFF FF FF)
 *   [10..N] message body
 *
 * Wire format with 14-byte DLI preamble (prepended before the frame above):
 *   [0]     version  = 0x00
 *   [1]     MFID     (0x00 = standard, 0x90 = Motorola)
 *   [2]     algid    = 0x80 (clear)
 *   [3..4]  key id   = 0x00 0x00
 *   [5..13] MI       = 0x00 × 9
 */
public class KmmFrame {

    public KmmBody kmmBody;

    public KmmFrame(KmmBody body) {
        this.kmmBody = body;
    }

    /** Deserialize from raw bytes (no preamble). */
    public KmmFrame(boolean withPreamble, byte[] data) throws IOException {
        if (withPreamble) {
            parseWithPreamble(data);
        } else {
            parse(0x00, data);
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    public byte[] toBytes() {
        byte[] body = kmmBody.toBytes();
        int length = 10 + body.length;
        byte[] frame = new byte[length];

        frame[0] = (byte) kmmBody.getMessageId();

        int messageLength = 7 + body.length;
        frame[1] = (byte) ((messageLength >> 8) & 0xFF);
        frame[2] = (byte) (messageLength & 0xFF);

        // message format: bits 7:6 = response kind
        int rk = kmmBody.getResponseKind();
        frame[3] = (byte) (((rk & 0x02) << 6) | ((rk & 0x01) << 7));

        // destination RSI = broadcast
        frame[4] = (byte) 0xFF;
        frame[5] = (byte) 0xFF;
        frame[6] = (byte) 0xFF;

        // source RSI = broadcast
        frame[7] = (byte) 0xFF;
        frame[8] = (byte) 0xFF;
        frame[9] = (byte) 0xFF;

        System.arraycopy(body, 0, frame, 10, body.length);
        return frame;
    }

    /**
     * Prepend a 14-byte DLI preamble before the standard KMM frame bytes.
     * Preamble layout: [version=0x00][mfid][algid=0x80][keyId×2][MI×9]
     * Mirrors KmmFrame.ToBytesWithPreamble() from the .NET source.
     */
    public byte[] toBytesWithPreamble(int mfid) {
        byte[] frame = toBytes();
        byte[] out   = new byte[14 + frame.length];
        out[0] = 0x00;        // version
        out[1] = (byte) mfid; // mfid
        out[2] = (byte) 0x80; // algid = clear
        out[3] = 0x00;        // key id hi
        out[4] = 0x00;        // key id lo
        // bytes 5-13 = 9 MI bytes, already zero from new byte[]
        System.arraycopy(frame, 0, out, 14, frame.length);
        return out;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private void parse(int mfid, byte[] contents) throws IOException {
        if (contents.length < 10) {
            throw new IOException("KMM frame too short: " + contents.length);
        }
        int messageId     = contents[0] & 0xFF;
        int messageLength = ((contents[1] & 0xFF) << 8) | (contents[2] & 0xFF);
        int bodyLength    = messageLength - 7;
        if (bodyLength < 0 || 10 + bodyLength > contents.length) {
            throw new IOException("KMM frame body length mismatch");
        }
        byte[] body = new byte[bodyLength];
        System.arraycopy(contents, 10, body, 0, bodyLength);
        kmmBody = parseBody(messageId, mfid, body);
    }

    private void parseWithPreamble(byte[] contents) throws IOException {
        if (contents.length < 14) throw new IOException("KMM preamble too short");
        int mfid = contents[1] & 0xFF;
        byte[] frame = new byte[contents.length - 14];
        System.arraycopy(contents, 14, frame, 0, frame.length);
        parse(mfid, frame);
    }

    private static KmmBody parseBody(int messageId, int mfid, byte[] body) throws IOException {
        if (messageId == MessageId.InventoryResponse) {
            if (body.length == 0) throw new IOException("Inventory response body is empty");
            int invType = body[0] & 0xFF;
            if (invType == 0x02) {
                InventoryResponseListActiveKsetIds b = new InventoryResponseListActiveKsetIds();
                b.parse(body);
                return b;
            } else if (invType == InventoryCommandListActiveKeys.INVENTORY_TYPE) {
                InventoryResponseListActiveKeys b = new InventoryResponseListActiveKeys();
                b.parse(body);
                return b;
            }
            throw new IOException("Unsupported inventory response type: 0x" + String.format(Locale.ROOT, "%02X", invType));

        } else if (messageId == MessageId.SessionControl) {
            if (body.length < 1) throw new IOException("SessionControl body empty");
            int version = body[0] & 0xFF;
            if (version == SessionControl.VERSION) {
                SessionControl b = new SessionControl();
                b.parse(body);
                return b;
            } else if (version == Mfid90SessionControlVer1.VERSION) {
                Mfid90SessionControlVer1 b = new Mfid90SessionControlVer1();
                b.parse(body);
                return b;
            }
            throw new IOException("Unknown SessionControl version: 0x" + String.format(Locale.ROOT, "%02X", version));

        } else if (messageId == MessageId.ZeroizeResponse) {
            ZeroizeResponse b = new ZeroizeResponse();
            b.parse(body);
            return b;

        } else if (messageId == MessageId.ModifyKeyCommand) {
            ModifyKeyCommand b = new ModifyKeyCommand();
            b.parse(body);
            return b;

        } else if (messageId == MessageId.RekeyAcknowledgment) {
            RekeyAcknowledgment b = new RekeyAcknowledgment();
            b.parse(body);
            return b;

        } else if (messageId == MessageId.NegativeAcknowledgment) {
            NegativeAcknowledgment b = new NegativeAcknowledgment();
            b.parse(body);
            return b;
        }
        throw new IOException("Unknown KMM message ID: 0x" + String.format(Locale.ROOT, "%02X", messageId));
    }
}
