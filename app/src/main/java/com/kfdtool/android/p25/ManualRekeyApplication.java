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
package com.kfdtool.android.p25;

import com.kfdtool.android.model.CmdKeyItem;
import com.kfdtool.android.p25.kmm.InventoryCommandListActiveKeys;
import com.kfdtool.android.p25.kmm.InventoryCommandListActiveKsetIds;
import com.kfdtool.android.p25.kmm.InventoryResponseListActiveKeys;
import com.kfdtool.android.p25.kmm.InventoryResponseListActiveKsetIds;
import com.kfdtool.android.p25.kmm.KeyInfo;
import com.kfdtool.android.p25.kmm.KeyItem;
import com.kfdtool.android.p25.kmm.KmmBody;
import com.kfdtool.android.p25.kmm.KmmFrame;
import com.kfdtool.android.p25.kmm.ModifyKeyCommand;
import com.kfdtool.android.p25.kmm.NegativeAcknowledgment;
import com.kfdtool.android.p25.kmm.OperationStatus;
import com.kfdtool.android.p25.kmm.RekeyAcknowledgment;
import com.kfdtool.android.p25.kmm.ZeroizeCommand;
import com.kfdtool.android.p25.kmm.ZeroizeResponse;
import com.kfdtool.android.protocol.AdapterProtocol;
import com.kfdtool.android.protocol.UdpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates P25 key management operations over either the three-wire interface (TWI)
 * or the data-link-independent interface (DLI).  All public methods must be called from
 * a background thread.
 */
public class ManualRekeyApplication {

    public interface ProgressListener {
        void onStatus(String message);
    }

    // Transport-agnostic protocol layer
    private final DeviceProtocol deviceProtocol;

    // Whether to wrap KMM frames with the 14-byte DLI preamble
    private final boolean withPreamble;

    // MFID byte used in the preamble (0x00 = standard, 0x90 = Motorola)
    private final int mfid;

    // Max keys to request per ViewKeyInfo page (differs by transport)
    private final int maxKeysPerPage;

    private ProgressListener progressListener;

    /** TWI constructor — communicates via KFD adapter over USB serial. */
    public ManualRekeyApplication(AdapterProtocol adapter) {
        this.deviceProtocol  = new ThreeWireProtocol(adapter);
        this.withPreamble    = false;
        this.mfid            = 0x00;
        this.maxKeysPerPage  = 78; // TIA 102.AACD-A 3.9.2.11
    }

    /** DLI constructor — communicates via UDP (Bluetooth PAN / USB RNDIS). */
    public ManualRekeyApplication(UdpTransport udp, boolean motVariant) {
        this.deviceProtocol  = new DataLinkIndependentProtocol(udp, motVariant);
        this.withPreamble    = true;
        this.mfid            = motVariant
                ? DataLinkIndependentProtocol.MFID_MOTOROLA
                : DataLinkIndependentProtocol.MFID_STANDARD;
        this.maxKeysPerPage  = 75; // to match KVL4000 1.3.5000.243 behavior
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
        deviceProtocol.setDebugListener(
                listener != null ? msg -> listener.onStatus("  " + msg) : null);
    }

    private void status(String msg) {
        if (progressListener != null) progressListener.onStatus(msg);
    }

    private DeviceType begin() throws IOException, InterruptedException {
        status("Sending key signature…");
        deviceProtocol.sendKeySignature();
        status("Initiating session…");
        return deviceProtocol.initSession();
    }

    private void end() throws IOException, InterruptedException {
        status("Ending session…");
        deviceProtocol.endSession();
    }

    private KmmBody txRxKmm(KmmBody cmdBody) throws IOException, InterruptedException {
        KmmFrame cmdFrame = new KmmFrame(cmdBody);
        byte[] toRadio = withPreamble
                ? cmdFrame.toBytesWithPreamble(mfid)
                : cmdFrame.toBytes();
        byte[] fromRadio = deviceProtocol.performKmmTransfer(toRadio);
        KmmFrame rspFrame = new KmmFrame(deviceProtocol.lastResponseHadPreamble(), fromRadio);
        return rspFrame.kmmBody;
    }

    // -------------------------------------------------------------------------
    // Keyload
    // -------------------------------------------------------------------------

    /**
     * Load a list of keys into the radio.
     * Keys with the same algorithmId+keysetId are batched into one ModifyKeyCommand,
     * matching the .NET KeyPartitioner behaviour.
     */
    public void keyload(List<CmdKeyItem> keyItems) throws IOException, InterruptedException {
        deviceProtocol.clearQueues();
        DeviceType deviceType = begin();

        try {
            int activeKeysetId = 1;

            if (deviceType == DeviceType.Mr) {
                status("Querying active keyset IDs…");
                KmmBody rsp = txRxKmm(new InventoryCommandListActiveKsetIds());

                if (rsp instanceof InventoryResponseListActiveKsetIds) {
                    InventoryResponseListActiveKsetIds inv = (InventoryResponseListActiveKsetIds) rsp;
                    if (!inv.ksetIds.isEmpty()) activeKeysetId = inv.ksetIds.get(0);
                    status("Active keyset ID: " + activeKeysetId);
                } else if (rsp instanceof NegativeAcknowledgment) {
                    NegativeAcknowledgment nack = (NegativeAcknowledgment) rsp;
                    throw new IOException("NAK on inventory: " + OperationStatus.toStatusString(nack.status));
                } else {
                    throw new IOException("Unexpected response to inventory: " + rsp.getClass().getSimpleName());
                }
            }

            LinkedHashMap<String, List<CmdKeyItem>> groups = new LinkedHashMap<>();
            for (CmdKeyItem item : keyItems) {
                String groupKey = item.algorithmId + ":" + item.keysetId;
                if (!groups.containsKey(groupKey)) groups.put(groupKey, new ArrayList<>());
                groups.get(groupKey).add(item);
            }

            for (Map.Entry<String, List<CmdKeyItem>> entry : groups.entrySet()) {
                List<CmdKeyItem> group = entry.getValue();
                CmdKeyItem first = group.get(0);

                ModifyKeyCommand cmd = new ModifyKeyCommand();
                cmd.keysetId    = first.keysetId;
                cmd.algorithmId = first.algorithmId;

                for (CmdKeyItem item : group) {
                    status(String.format("  Key — AlgID: 0x%02X, KsetID: %d, KeyID: %d, SLN: %d",
                            item.algorithmId, item.keysetId, item.keyId, item.sln));
                    KeyItem ki = new KeyItem();
                    ki.erase = false;
                    ki.kek   = item.kek;
                    ki.sln   = item.sln;
                    ki.keyId = item.keyId;
                    ki.key   = item.key;
                    cmd.keyItems.add(ki);
                }

                status(String.format("Sending ModifyKeyCommand (%d key(s), AlgID 0x%02X, KsetID %d)…",
                        group.size(), first.algorithmId, first.keysetId));

                KmmBody rsp = txRxKmm(cmd);

                if (rsp instanceof RekeyAcknowledgment) {
                    RekeyAcknowledgment ack = (RekeyAcknowledgment) rsp;
                    boolean allGood = true;
                    for (int i = 0; i < ack.keys.size(); i++) {
                        int s = ack.keys.get(i).status;
                        if (s != 0x00) {
                            allGood = false;
                            status("Key " + i + " status: " + OperationStatus.toStatusString(s));
                        }
                    }
                    if (allGood) status("Keys loaded successfully.");
                } else if (rsp instanceof NegativeAcknowledgment) {
                    NegativeAcknowledgment nack = (NegativeAcknowledgment) rsp;
                    throw new IOException("NAK on keyload: " + OperationStatus.toStatusString(nack.status));
                } else {
                    throw new IOException("Unexpected response to keyload: " + rsp.getClass().getSimpleName());
                }
            }
        } catch (IOException | InterruptedException e) {
            end();
            throw e;
        }

        end();
    }

    // -------------------------------------------------------------------------
    // Erase Key(s)
    // -------------------------------------------------------------------------

    /**
     * Erase specific keys by SLN.  Uses the radio's active keyset.
     * Algorithm, keyId, and key material are hardcoded to match KVL3000+ R3.53.03 behavior.
     */
    public void eraseKeys(List<CmdKeyItem> keyItems) throws IOException, InterruptedException {
        deviceProtocol.clearQueues();
        begin();

        try {
            status("Querying active keyset IDs…");
            KmmBody rsp1 = txRxKmm(new InventoryCommandListActiveKsetIds());
            int activeKeysetId = 1;

            if (rsp1 instanceof InventoryResponseListActiveKsetIds) {
                InventoryResponseListActiveKsetIds inv = (InventoryResponseListActiveKsetIds) rsp1;
                if (!inv.ksetIds.isEmpty()) activeKeysetId = inv.ksetIds.get(0);
                status("Active keyset ID: " + activeKeysetId);
            } else if (rsp1 instanceof NegativeAcknowledgment) {
                NegativeAcknowledgment nack = (NegativeAcknowledgment) rsp1;
                throw new IOException("NAK on inventory: " + OperationStatus.toStatusString(nack.status));
            } else {
                throw new IOException("Unexpected response to inventory: " + rsp1.getClass().getSimpleName());
            }

            ModifyKeyCommand cmd = new ModifyKeyCommand();
            cmd.keysetId    = activeKeysetId;
            cmd.algorithmId = 0x81; // to match KVL3000+ R3.53.03 behavior

            for (CmdKeyItem item : keyItems) {
                status(String.format("  Erase — SLN: %d", item.sln));
                KeyItem ki = new KeyItem();
                ki.erase = true;
                ki.kek   = false;
                ki.sln   = item.sln;
                ki.keyId = 65535;
                ki.key   = new byte[]{ (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
                                       (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF };
                cmd.keyItems.add(ki);
            }

            status(String.format("Sending erase command (%d key(s))…", keyItems.size()));
            KmmBody rsp2 = txRxKmm(cmd);

            if (rsp2 instanceof RekeyAcknowledgment) {
                RekeyAcknowledgment ack = (RekeyAcknowledgment) rsp2;
                boolean allGood = true;
                for (int i = 0; i < ack.keys.size(); i++) {
                    int s = ack.keys.get(i).status;
                    if (s != 0x00) {
                        allGood = false;
                        status("Key " + i + " status: " + OperationStatus.toStatusString(s));
                    }
                }
                if (allGood) status("Key(s) erased successfully.");
            } else if (rsp2 instanceof NegativeAcknowledgment) {
                NegativeAcknowledgment nack = (NegativeAcknowledgment) rsp2;
                throw new IOException("NAK on erase: " + OperationStatus.toStatusString(nack.status));
            } else {
                throw new IOException("Unexpected response to erase: " + rsp2.getClass().getSimpleName());
            }
        } catch (IOException | InterruptedException e) {
            end();
            throw e;
        }

        end();
    }

    // -------------------------------------------------------------------------
    // Erase All Keys (Zeroize)
    // -------------------------------------------------------------------------

    /** Send a Zeroize Command to erase all keys stored in the radio. */
    public void eraseAllKeys() throws IOException, InterruptedException {
        deviceProtocol.clearQueues();
        begin();

        try {
            status("Sending Zeroize Command…");
            KmmBody rsp = txRxKmm(new ZeroizeCommand());

            if (rsp instanceof ZeroizeResponse) {
                status("All keys erased.");
            } else if (rsp instanceof NegativeAcknowledgment) {
                NegativeAcknowledgment nack = (NegativeAcknowledgment) rsp;
                throw new IOException("NAK on zeroize: " + OperationStatus.toStatusString(nack.status));
            } else {
                throw new IOException("Unexpected response to zeroize: " + rsp.getClass().getSimpleName());
            }
        } catch (IOException | InterruptedException e) {
            end();
            throw e;
        }

        end();
    }

    // -------------------------------------------------------------------------
    // View Key Info
    // -------------------------------------------------------------------------

    /**
     * Query all active keys stored in the radio.
     * Handles pagination via the inventory marker (marker=0 means no more pages).
     */
    public List<KeyInfo> viewKeyInfo() throws IOException, InterruptedException {
        List<KeyInfo> result = new ArrayList<>();
        deviceProtocol.clearQueues();
        begin();

        try {
            boolean more = true;
            int marker = 0;

            while (more) {
                InventoryCommandListActiveKeys cmd = new InventoryCommandListActiveKeys();
                cmd.inventoryMarker  = marker;
                cmd.maxKeysRequested = maxKeysPerPage;

                status("Querying active keys (marker=" + marker + ")…");
                KmmBody rsp = txRxKmm(cmd);

                if (rsp instanceof InventoryResponseListActiveKeys) {
                    InventoryResponseListActiveKeys inv = (InventoryResponseListActiveKeys) rsp;
                    marker = inv.inventoryMarker;
                    if (marker == 0) more = false;
                    result.addAll(inv.keys);
                    status("Received " + inv.keys.size() + " key(s).");
                } else if (rsp instanceof NegativeAcknowledgment) {
                    NegativeAcknowledgment nack = (NegativeAcknowledgment) rsp;
                    throw new IOException("NAK on view key info: " + OperationStatus.toStatusString(nack.status));
                } else {
                    throw new IOException("Unexpected response to key inventory: " + rsp.getClass().getSimpleName());
                }
            }
        } catch (IOException | InterruptedException e) {
            end();
            throw e;
        }

        end();
        return result;
    }
}
