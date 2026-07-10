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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import java.security.SecureRandom;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kfdtool.android.AppSettings;
import com.kfdtool.android.MainActivity;
import com.kfdtool.android.databinding.FragmentKeyloadBinding;
import com.kfdtool.android.model.AlgorithmId;
import com.kfdtool.android.model.CmdKeyItem;

import java.util.ArrayList;
import java.util.List;

public class KeyloadFragment extends Fragment {

    private FragmentKeyloadBinding binding;
    private AppSettings settings;
    private boolean prevKeyIdHex;
    private boolean prevSlnHex;

    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (prefs, key) -> {
                if (!isAdded() || binding == null || settings == null) return;
                if (AppSettings.KEY_ID_HEX.equals(key)) {
                    boolean nowHex = prefs.getBoolean(key, false);
                    reformatField(binding.etKeyId, prevKeyIdHex, nowHex);
                    prevKeyIdHex = nowHex;
                    settings = new AppSettings(requireContext());
                    binding.tilKeyId.setHint(settings.keyIdHint());
                    binding.etKeyId.setInputType(settings.keyIdInputType());
                } else if (AppSettings.SLN_HEX.equals(key)) {
                    boolean nowHex = prefs.getBoolean(key, false);
                    reformatField(binding.etSln, prevSlnHex, nowHex);
                    prevSlnHex = nowHex;
                    settings = new AppSettings(requireContext());
                    binding.tilSln.setHint(settings.slnHint());
                    binding.etSln.setInputType(settings.slnInputType());
                    updateKeyTypeLabel();
                }
            };

    private void reformatField(com.google.android.material.textfield.TextInputEditText et,
                                boolean wasHex, boolean isHex) {
        if (wasHex == isHex || et == null) return;
        String raw = et.getText() != null ? et.getText().toString().trim() : "";
        if (raw.isEmpty()) return;
        try {
            int val = AppSettings.parseHexOrDec(raw, wasHex);
            et.setText(isHex ? String.format("0x%04X", val) : String.valueOf(val));
        } catch (NumberFormatException ignored) {}
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentKeyloadBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        settings = new AppSettings(requireContext());
        prevKeyIdHex = settings.isKeyIdHex();
        prevSlnHex   = settings.isSlnHex();
        settings.getPrefs().registerOnSharedPreferenceChangeListener(settingsListener);
        applySettings();

        // Populate algorithm spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, AlgorithmId.DISPLAY_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerAlgorithm.setAdapter(adapter);

        // Show/hide Other ALGID field; disable key generation for Other; re-validate all on change.
        // isInitialSelection guards against the automatic onItemSelected fired when the adapter
        // is first attached — we don't want to flash validation errors on a pristine form.
        final boolean[] isInitialSelection = {true};
        binding.spinnerAlgorithm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                boolean isOther = AlgorithmId.VALUES[pos] == AlgorithmId.OTHER;
                binding.tilOtherAlgId.setVisibility(isOther ? View.VISIBLE : View.GONE);
                binding.btnGenerateKey.setVisibility(isOther ? View.GONE : View.VISIBLE);
                if (isInitialSelection[0]) { isInitialSelection[0] = false; return; }
                validateAll();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // Focus loss on any field validates all fields
        binding.etOtherAlgId.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) validateAll(); });
        binding.etKeysetId.setOnFocusChangeListener((v, hasFocus)   -> { if (!hasFocus) validateAll(); });
        binding.etKeyId.setOnFocusChangeListener((v, hasFocus)      -> { if (!hasFocus) { normalizeHex(binding.etKeyId, settings.isKeyIdHex()); validateAll(); } });
        binding.etSln.setOnFocusChangeListener((v, hasFocus)        -> { if (!hasFocus) { normalizeHex(binding.etSln,   settings.isSlnHex());   validateAll(); } });
        binding.etKeyHex.setOnFocusChangeListener((v, hasFocus)     -> { if (!hasFocus) validateAll(); });

        // Auto key type detection: SLN 0–61439 = TEK, SLN 61440–65534 = KEK
        binding.etSln.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateKeyTypeLabel(); }
        });

        // Disable Keyset ID when Active Keyset is checked
        binding.cbActiveKeyset.setOnCheckedChangeListener(
                (btn, checked) -> binding.tilKeysetId.setEnabled(!checked));
        binding.tilKeysetId.setEnabled(!binding.cbActiveKeyset.isChecked());

        binding.btnGenerateKey.setOnClickListener(v -> {
            int algId = resolveAlgId();
            int len = AlgorithmId.keyLength(algId);
            if (len == 0) {
                Toast.makeText(requireContext(), "CLEAR has no key material", Toast.LENGTH_SHORT).show();
                return;
            }
            if (len < 0) len = 32;
            byte[] keyBytes = new byte[len];
            new SecureRandom().nextBytes(keyBytes);
            binding.etKeyHex.setText(bytesToHex(keyBytes));
        });

        binding.btnKeyload.setOnClickListener(v -> startKeyload());

        refreshButtons();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshButtons();
    }

    private void refreshButtons() {
        if (binding == null) return;
        binding.btnKeyload.setEnabled(
                ((MainActivity) requireActivity()).isConnected() && isFormComplete());
    }

    private int resolveAlgId() {
        int pos = binding.spinnerAlgorithm.getSelectedItemPosition();
        if (AlgorithmId.VALUES[pos] != AlgorithmId.OTHER) return AlgorithmId.VALUES[pos];
        String hex = binding.etOtherAlgId.getText() != null
                ? binding.etOtherAlgId.getText().toString().trim().replaceFirst("(?i)^0x", "")
                : "";
        try {
            int v = Integer.parseInt(hex, 16);
            return (v >= 0 && v <= 0xFF) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void startKeyload() {
        int algId = resolveAlgId();
        if (algId < 0) {
            Toast.makeText(requireContext(), "Enter a valid Algorithm ID (hex 00–FF)", Toast.LENGTH_SHORT).show();
            return;
        }

        String keyHex = binding.etKeyHex.getText() != null
                ? binding.etKeyHex.getText().toString().trim().replaceAll("\\s+", "")
                : "";
        if (keyHex.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a key hex value", Toast.LENGTH_SHORT).show();
            return;
        }
        if (keyHex.length() % 2 != 0) {
            Toast.makeText(requireContext(), "Key hex must have an even number of characters", Toast.LENGTH_SHORT).show();
            return;
        }

        byte[] keyBytes;
        try {
            keyBytes = hexToBytes(keyHex);
        } catch (IllegalArgumentException e) {
            Toast.makeText(requireContext(), "Invalid hex: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        boolean isOther = AlgorithmId.VALUES[binding.spinnerAlgorithm.getSelectedItemPosition()] == AlgorithmId.OTHER;
        int expectedLen = AlgorithmId.keyLength(algId);
        if (!isOther && expectedLen > 0 && keyBytes.length != expectedLen) {
            Toast.makeText(requireContext(),
                    String.format("Key must be %d bytes (%d hex chars) for this algorithm",
                            expectedLen, expectedLen * 2),
                    Toast.LENGTH_LONG).show();
            return;
        }

        int keysetId, keyId, sln;
        try {
            keysetId = Integer.parseInt(binding.etKeysetId.getText().toString().trim());
            keyId    = settings.parseKeyId(binding.etKeyId.getText().toString().trim());
            sln      = settings.parseSln(binding.etSln.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "Invalid numeric field", Toast.LENGTH_SHORT).show();
            return;
        }

        if (keysetId < 1 || keysetId > 255) {
            Toast.makeText(requireContext(), "Keyset ID must be 1–255", Toast.LENGTH_SHORT).show();
            return;
        }
        if (keyId < 0 || keyId > 65535) {
            Toast.makeText(requireContext(), settings.keyIdRangeError(), Toast.LENGTH_SHORT).show();
            return;
        }
        if (sln < 0 || sln > 65535) {
            Toast.makeText(requireContext(), settings.slnRangeError(), Toast.LENGTH_SHORT).show();
            return;
        }

        CmdKeyItem item = new CmdKeyItem(algId, keysetId, keyId, sln, keyBytes);
        item.activeKeyset = binding.cbActiveKeyset.isChecked();
        item.kek          = isKekSln(sln);
        List<CmdKeyItem> items = new ArrayList<>();
        items.add(item);

        binding.btnKeyload.setEnabled(false);
        binding.cardKeyloadStatus.setVisibility(View.VISIBLE);
        binding.tvKeyloadStatus.setText("Starting keyload…");

        MainActivity act = (MainActivity) requireActivity();
        act.performKeyload(items, (success, message, debugLog) -> {
            act.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                binding.btnKeyload.setEnabled(true);
                binding.tvKeyloadStatus.setText(message);
                if (debugLog != null && !debugLog.isEmpty()) {
                    showDebugDialog(debugLog);
                }
            });
        });
    }

    private void showDebugDialog(String debugLog) {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Debug Log")
                .setMessage(debugLog)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2),     16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Non-hex character");
            result[i] = (byte)((hi << 4) | lo);
        }
        return result;
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private void applySettings() {
        if (binding == null || settings == null) return;
        binding.tilKeyId.setHint(settings.keyIdHint());
        binding.tilSln.setHint(settings.slnHint());
        binding.etKeyId.setInputType(settings.keyIdInputType());
        binding.etSln.setInputType(settings.slnInputType());
    }

    // ── Key type detection ────────────────────────────────────────────────────

    /** SLN 61440–65534 (0xF000–0xFFFE) = KEK; SLN 0–61439 = TEK. */
    private static boolean isKekSln(int sln) {
        return sln >= 61440 && sln <= 65534;
    }

    private void updateKeyTypeLabel() {
        if (binding == null || settings == null) return;
        String raw = binding.etSln.getText() != null
                ? binding.etSln.getText().toString().trim() : "";
        boolean isKek = false;
        try { isKek = isKekSln(settings.parseSln(raw)); } catch (NumberFormatException ignored) {}
        binding.tvKeyType.setText(isKek ? "Key Type: KEK" : "Key Type: TEK");
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

    private void validateAll() {
        if (binding == null || settings == null) return;
        updateKeyTypeLabel();
        boolean isOther = AlgorithmId.VALUES[
                binding.spinnerAlgorithm.getSelectedItemPosition()] == AlgorithmId.OTHER;
        if (isOther) validateOtherAlgId();
        validateKeysetId();
        validateKeyId();
        validateSln();
        validateKeyHex();
        refreshButtons();
    }

    private void validateOtherAlgId() {
        String hex = binding.etOtherAlgId.getText() != null
                ? binding.etOtherAlgId.getText().toString().trim().replaceFirst("(?i)^0x", "") : "";
        if (hex.isEmpty()) { setError(binding.tilOtherAlgId, null); return; }
        try {
            int v = Integer.parseInt(hex, 16);
            setError(binding.tilOtherAlgId, (v >= 0 && v <= 0xFF) ? null : "Must be 00-FF");
        } catch (NumberFormatException e) {
            setError(binding.tilOtherAlgId, "Invalid hex");
        }
    }

    private void validateKeysetId() {
        String raw = binding.etKeysetId.getText() != null
                ? binding.etKeysetId.getText().toString().trim() : "";
        if (raw.isEmpty()) { setError(binding.tilKeysetId, null); return; }
        try {
            int v = Integer.parseInt(raw);
            setError(binding.tilKeysetId, (v >= 1 && v <= 255) ? null : "Must be 1–255");
        } catch (NumberFormatException e) {
            setError(binding.tilKeysetId, "Invalid number");
        }
    }

    private void validateKeyId() {
        String raw = binding.etKeyId.getText() != null
                ? binding.etKeyId.getText().toString().trim() : "";
        if (raw.isEmpty()) { setError(binding.tilKeyId, null); return; }
        try {
            int v = settings.parseKeyId(raw);
            setError(binding.tilKeyId, (v >= 0 && v <= 65535) ? null : settings.keyIdRangeError());
        } catch (NumberFormatException e) {
            setError(binding.tilKeyId, settings.keyIdInvalidError());
        }
    }

    private void validateSln() {
        String raw = binding.etSln.getText() != null
                ? binding.etSln.getText().toString().trim() : "";
        if (raw.isEmpty()) { setError(binding.tilSln, null); return; }
        try {
            int v = settings.parseSln(raw);
            setError(binding.tilSln, (v >= 0 && v <= 65535) ? null : settings.slnRangeError());
        } catch (NumberFormatException e) {
            setError(binding.tilSln, settings.slnInvalidError());
        }
    }

    private void validateKeyHex() {
        String hex = binding.etKeyHex.getText() != null
                ? binding.etKeyHex.getText().toString().trim().replaceAll("\\s+", "") : "";
        if (hex.isEmpty()) { setError(binding.tilKeyHex, null); return; }
        if (hex.length() % 2 != 0) {
            setError(binding.tilKeyHex, "Must have an even number of hex characters");
            return;
        }
        try {
            hexToBytes(hex);
        } catch (IllegalArgumentException e) {
            setError(binding.tilKeyHex, "Invalid hex characters");
            return;
        }
        int algId = resolveAlgId();
        boolean isOther = AlgorithmId.VALUES[binding.spinnerAlgorithm.getSelectedItemPosition()] == AlgorithmId.OTHER;
        int expectedLen = AlgorithmId.keyLength(algId);
        if (!isOther && expectedLen > 0 && hex.length() / 2 != expectedLen) {
            setError(binding.tilKeyHex, String.format("Expected %d bytes (%d hex chars) for this algorithm",
                    expectedLen, expectedLen * 2));
            return;
        }
        setError(binding.tilKeyHex, null);
    }

    private boolean isFormComplete() {
        if (binding == null || settings == null) return false;
        boolean isOther = AlgorithmId.VALUES[
                binding.spinnerAlgorithm.getSelectedItemPosition()] == AlgorithmId.OTHER;
        try {
            String keysetRaw = binding.etKeysetId.getText() != null
                    ? binding.etKeysetId.getText().toString().trim() : "";
            if (keysetRaw.isEmpty()) return false;
            int ks = Integer.parseInt(keysetRaw);
            if (ks < 1 || ks > 255) return false;

            String keyIdRaw = binding.etKeyId.getText() != null
                    ? binding.etKeyId.getText().toString().trim() : "";
            if (keyIdRaw.isEmpty()) return false;
            int kid = settings.parseKeyId(keyIdRaw);
            if (kid < 0 || kid > 65535) return false;

            String slnRaw = binding.etSln.getText() != null
                    ? binding.etSln.getText().toString().trim() : "";
            if (slnRaw.isEmpty()) return false;
            int sln = settings.parseSln(slnRaw);
            if (sln < 0 || sln > 65535) return false;

            String hex = binding.etKeyHex.getText() != null
                    ? binding.etKeyHex.getText().toString().trim().replaceAll("\\s+", "") : "";
            if (hex.isEmpty() || hex.length() % 2 != 0) return false;
            hexToBytes(hex);

            if (isOther) {
                String algHex = binding.etOtherAlgId.getText() != null
                        ? binding.etOtherAlgId.getText().toString().trim().replaceFirst("(?i)^0x", "") : "";
                if (algHex.isEmpty()) return false;
                int algId = Integer.parseInt(algHex, 16);
                if (algId < 0 || algId > 0xFF) return false;
            } else {
                int expectedLen = AlgorithmId.keyLength(resolveAlgId());
                if (expectedLen > 0 && hex.length() / 2 != expectedLen) return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private void setError(com.google.android.material.textfield.TextInputLayout til, String error) {
        if (error != null) {
            til.setError(error);
        } else {
            til.setError(null);
            til.setErrorEnabled(false);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (settings != null) settings.getPrefs().unregisterOnSharedPreferenceChangeListener(settingsListener);
        binding = null;
    }
}
