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
package com.kfdtool.android.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kfdtool.android.AppSettings;
import com.kfdtool.android.MainActivity;
import com.kfdtool.android.databinding.FragmentRadioKeysBinding;
import com.kfdtool.android.model.CmdKeyItem;
import com.kfdtool.android.p25.kmm.KeyInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment for radio key management: view key info, erase a key, erase all keys.
 */
public class RadioKeysFragment extends Fragment {

    private FragmentRadioKeysBinding binding;
    private AppSettings settings;
    private boolean prevSlnHex;

    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (prefs, key) -> {
                if (!isAdded() || binding == null || settings == null) return;
                if (AppSettings.SLN_HEX.equals(key)) {
                    boolean nowHex = prefs.getBoolean(key, false);
                    String raw = binding.etEraseSln.getText() != null
                            ? binding.etEraseSln.getText().toString().trim() : "";
                    if (!raw.isEmpty()) {
                        try {
                            int val = AppSettings.parseHexOrDec(raw, prevSlnHex);
                            binding.etEraseSln.setText(nowHex
                                    ? String.format("0x%04X", val) : String.valueOf(val));
                        } catch (NumberFormatException ignored) {}
                    }
                    prevSlnHex = nowHex;
                    settings = new AppSettings(requireContext());
                    applySettings();
                }
            };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentRadioKeysBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settings = new AppSettings(requireContext());
        prevSlnHex = settings.isSlnHex();
        settings.getPrefs().registerOnSharedPreferenceChangeListener(settingsListener);
        applySettings();

        binding.btnViewKeyInfo.setOnClickListener(v -> startViewKeyInfo());
        binding.btnEraseKey.setOnClickListener(v -> confirmEraseKey());
        binding.btnEraseAllKeys.setOnClickListener(v -> confirmEraseAllKeys());

        refreshButtons();

        binding.etEraseSln.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                normalizeHex(binding.etEraseSln, settings.isSlnHex());
                validateEraseSln();
            }
        });
    }

    // ── View Key Info ──────────────────────────────────────────────────────────

    private void startViewKeyInfo() {
        binding.btnViewKeyInfo.setEnabled(false);
        binding.cardKeyInfoResult.setVisibility(View.VISIBLE);
        binding.tvKeyInfoResult.setText("Reading key info…");

        MainActivity act = (MainActivity) requireActivity();
        act.performViewKeyInfo((success, message, keys, debugLog) ->
            act.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                binding.btnViewKeyInfo.setEnabled(true);
                if (success && keys != null) {
                    binding.tvKeyInfoResult.setText(formatKeyTable(keys));
                } else {
                    binding.tvKeyInfoResult.setText(message);
                    if (debugLog != null && !debugLog.isEmpty()) {
                        showDebugDialog(debugLog);
                    }
                }
            })
        );
    }

    private String formatKeyTable(List<KeyInfo> keys) {
        if (keys.isEmpty()) return "0 key(s) found.";
        StringBuilder sb = new StringBuilder();
        sb.append(keys.size()).append(" key(s) found:\n\n");
        sb.append(String.format("%-7s %-9s %-7s %-9s\n", "KsetID", "SLN/CKR", "AlgID", "KeyID"));
        sb.append("------- --------- ------- ---------\n");
        for (KeyInfo ki : keys) {
            sb.append(String.format("%-7d %-9s 0x%02X    %-9s\n",
                    ki.keysetId, settings.formatSln(ki.sln), ki.algorithmId,
                    settings.formatKeyId(ki.keyId)));
        }
        return sb.toString();
    }

    // ── Erase Key ─────────────────────────────────────────────────────────────

    private void confirmEraseKey() {
        int sln;
        try {
            String raw = binding.etEraseSln.getText() != null
                    ? binding.etEraseSln.getText().toString().trim() : "";
            sln = settings.parseSln(raw);
        } catch (NumberFormatException e) {
            showEraseKeyStatus("Invalid SLN/CKR value.");
            return;
        }
        if (sln < 0 || sln > 65535) {
            showEraseKeyStatus(settings.slnRangeError());
            return;
        }

        final int finalSln = sln;
        new AlertDialog.Builder(requireContext())
                .setTitle("Confirm Erase Key")
                .setMessage("Erase the key at SLN/CKR " + settings.formatSln(sln) + "?\nThis cannot be undone.")
                .setPositiveButton("Erase", (d, w) -> startEraseKey(finalSln))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startEraseKey(int sln) {
        binding.btnEraseKey.setEnabled(false);
        showEraseKeyStatus("Starting erase…");

        CmdKeyItem item = new CmdKeyItem();
        item.sln = sln;
        List<CmdKeyItem> items = new ArrayList<>();
        items.add(item);

        MainActivity act = (MainActivity) requireActivity();
        act.performEraseKeys(items, (success, message, debugLog) ->
            act.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                binding.btnEraseKey.setEnabled(true);
                showEraseKeyStatus(message);
                if (debugLog != null && !debugLog.isEmpty()) {
                    showDebugDialog(debugLog);
                }
            })
        );
    }

    private void showEraseKeyStatus(String msg) {
        binding.cardEraseKeyStatus.setVisibility(View.VISIBLE);
        binding.tvEraseKeyStatus.setText(msg);
    }

    // ── Erase All Keys ────────────────────────────────────────────────────────

    private void confirmEraseAllKeys() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Confirm Erase All Keys")
                .setMessage("Erase ALL keys from the radio?\nThis cannot be undone.")
                .setPositiveButton("Erase All", (d, w) -> startEraseAllKeys())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startEraseAllKeys() {
        binding.btnEraseAllKeys.setEnabled(false);
        showEraseAllStatus("Starting zeroize…");

        MainActivity act = (MainActivity) requireActivity();
        act.performEraseAllKeys((success, message, debugLog) ->
            act.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                binding.btnEraseAllKeys.setEnabled(true);
                showEraseAllStatus(message);
                if (debugLog != null && !debugLog.isEmpty()) {
                    showDebugDialog(debugLog);
                }
            })
        );
    }

    private void showEraseAllStatus(String msg) {
        binding.cardEraseAllStatus.setVisibility(View.VISIBLE);
        binding.tvEraseAllStatus.setText(msg);
    }

    // ── Field validation ──────────────────────────────────────────────────────

    private static void normalizeHex(com.google.android.material.textfield.TextInputEditText et, boolean isHex) {
        if (!isHex || et == null) return;
        String raw = et.getText() != null ? et.getText().toString().trim() : "";
        if (raw.isEmpty()) return;
        try {
            int val = AppSettings.parseHexOrDec(raw, true);
            et.setText(String.format("0x%04X", val));
        } catch (NumberFormatException ignored) {}
    }

    private void validateEraseSln() {
        String raw = binding.etEraseSln.getText() != null
                ? binding.etEraseSln.getText().toString().trim() : "";
        if (raw.isEmpty()) {
            binding.tilEraseSln.setError("Required");
            return;
        }
        try {
            int v = settings.parseSln(raw);
            if (v >= 0 && v <= 65535) {
                binding.tilEraseSln.setError(null);
                binding.tilEraseSln.setErrorEnabled(false);
            } else {
                binding.tilEraseSln.setError(settings.slnRangeError());
            }
        } catch (NumberFormatException e) {
            binding.tilEraseSln.setError(settings.slnInvalidError());
        }
    }

    private void applySettings() {
        if (binding == null || settings == null) return;
        binding.tilEraseSln.setHint(settings.slnHint());
        binding.etEraseSln.setInputType(settings.slnInputType());
    }


    // ── Shared helpers ────────────────────────────────────────────────────────

    private void showDebugDialog(String debugLog) {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Debug Log")
                .setMessage(debugLog)
                .setPositiveButton("OK", null)
                .show();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        refreshButtons();
    }

    private void refreshButtons() {
        if (binding == null) return;
        boolean connected = ((MainActivity) requireActivity()).isConnected();
        binding.btnViewKeyInfo.setEnabled(connected);
        binding.btnEraseKey.setEnabled(connected);
        binding.btnEraseAllKeys.setEnabled(connected);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (settings != null) settings.getPrefs().unregisterOnSharedPreferenceChangeListener(settingsListener);
        binding = null;
    }
}
