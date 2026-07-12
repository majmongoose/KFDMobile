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
package com.sentinelcomms.kfddroid.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import java.security.SecureRandom;

import com.sentinelcomms.kfddroid.AppSettings;
import com.sentinelcomms.kfddroid.R;
import com.sentinelcomms.kfddroid.container.ContainerKey;
import com.sentinelcomms.kfddroid.databinding.DialogKeyEditBinding;
import com.sentinelcomms.kfddroid.model.AlgorithmId;
import com.sentinelcomms.kfddroid.model.DesKeyValidator;

/**
 * Dialog for adding or editing a ContainerKey.
 * Caller implements Listener and receives the result via onKeySaved().
 */
public class KeyEditDialogFragment extends DialogFragment {

    public interface Listener {
        void onKeySaved(ContainerKey key);
    }

    private static final String ARG_KEY_ID = "keyId";

    private DialogKeyEditBinding binding;
    private ContainerKey existing;
    private Listener listener;
    private AppSettings settings;

    /** Create dialog for a new key (id = -1 means new). */
    public static KeyEditDialogFragment newKey() {
        return new KeyEditDialogFragment();
    }

    /** Create dialog pre-filled with an existing key. */
    public static KeyEditDialogFragment editKey(ContainerKey key) {
        KeyEditDialogFragment f = new KeyEditDialogFragment();
        f.existing = key;
        return f;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogKeyEditBinding.inflate(getLayoutInflater());
        settings = new AppSettings(requireContext());

        // Apply number format settings to Key ID and SLN fields
        binding.tilKeyId.setHint(settings.keyIdHint());
        binding.tilSln.setHint(settings.slnHint());
        binding.etKeyId.setInputType(settings.keyIdInputType());
        binding.etSln.setInputType(settings.slnInputType());

        // Populate algorithm spinner
        ArrayAdapter<String> algAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, AlgorithmId.DISPLAY_NAMES);
        algAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerAlgorithm.setAdapter(algAdapter);

        // Show/hide Other ALGID field; disable key generation for Other; re-validate all on change.
        // isInitialSelection suppresses the automatic firing when the adapter is first attached.
        final boolean[] isInitialSelection = {true};
        binding.spinnerAlgorithm.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                boolean isOther = AlgorithmId.VALUES[pos] == AlgorithmId.OTHER;
                binding.tilOtherAlgId.setVisibility(isOther ? View.VISIBLE : View.GONE);
                binding.btnGenerateKey.setVisibility(isOther ? View.GONE : View.VISIBLE);
                applyKeyHexMaxLength();
                if (isInitialSelection[0]) { isInitialSelection[0] = false; return; }
                validateAll();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        applyKeyHexMaxLength();

        // Focus loss on any field validates all fields
        binding.etOtherAlgId.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) validateAll(); });
        binding.etKeysetId.setOnFocusChangeListener((v, hasFocus)   -> { if (!hasFocus) validateAll(); });
        binding.etKeyId.setOnFocusChangeListener((v, hasFocus)      -> { if (!hasFocus) { normalizeHex(binding.etKeyId, settings.isKeyIdHex()); validateAll(); } });
        binding.etSln.setOnFocusChangeListener((v, hasFocus)        -> { if (!hasFocus) { normalizeHex(binding.etSln,   settings.isSlnHex());   validateAll(); } });
        binding.etKeyHex.setOnFocusChangeListener((v, hasFocus)     -> { if (!hasFocus) validateAll(); });

        // SLN watcher: only for key type label (no full validation on every keystroke)
        binding.etSln.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateKeyTypeLabel(); }
        });
        updateKeyTypeLabel();

        // Live key-hex feedback: red only for non-hex characters or over-length.
        // "Still typing" (under expected length) stays silent until blur.
        binding.etKeyHex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { validateKeyHexLive(); }
        });

        // Disable Keyset ID when Active Keyset is checked (radio uses keyset 1 automatically)
        binding.cbActiveKeyset.setOnCheckedChangeListener(
                (btn, checked) -> binding.tilKeysetId.setEnabled(!checked));
        binding.tilKeysetId.setEnabled(!binding.cbActiveKeyset.isChecked());

        // Generate random key button (disabled when Other is selected)
        binding.btnGenerateKey.setOnClickListener(v -> {
            int algId = resolveAlgId();
            int len = AlgorithmId.keyLength(algId);
            if (len == 0) {
                Toast.makeText(requireContext(), "CLEAR has no key material", Toast.LENGTH_SHORT).show();
                return;
            }
            final int keyLen = len < 0 ? 32 : len;

            String existingText = binding.etKeyHex.getText() != null
                    ? binding.etKeyHex.getText().toString().trim() : "";
            if (!existingText.isEmpty()) {
                new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Replace key?")
                        .setMessage("There's already a key in the field. Replace it with a new random key?")
                        .setPositiveButton("Replace", (d, w) -> fillRandomKey(keyLen))
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            fillRandomKey(keyLen);
        });

        // Pre-fill if editing
        if (existing != null) {
            binding.etName.setText(existing.name);
            binding.etKeysetId.setText(String.valueOf(existing.keysetId));
            binding.etKeyId.setText(settings.formatKeyId(existing.keyId));
            binding.etSln.setText(settings.formatSln(existing.sln));
            // Hide key value by default when editing; user taps the eye icon to reveal
            binding.tilKeyHex.setEndIconMode(
                    com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
            binding.etKeyHex.setInputType(
                    android.text.InputType.TYPE_CLASS_TEXT |
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

            // Select the matching algorithm BEFORE setting the key hex so the length
            // filter has the correct cap and doesn't truncate a longer stored key.
            boolean found = false;
            for (int i = 0; i < AlgorithmId.VALUES.length; i++) {
                if (AlgorithmId.VALUES[i] == existing.algorithmId) {
                    binding.spinnerAlgorithm.setSelection(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                // Select "Other" and pre-fill the hex field
                binding.spinnerAlgorithm.setSelection(AlgorithmId.VALUES.length - 1);
                binding.etOtherAlgId.setText(String.format("%02X", existing.algorithmId));
            }
            binding.etKeyHex.setText(existing.keyHex);

            binding.cbActiveKeyset.setChecked(existing.activeKeyset);
        }

        String title = existing == null ? "Add Key" : "Edit Key";

        return new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(binding.getRoot())
                .setPositiveButton("Save", null) // null prevents auto-dismiss; listener set in onStart()
                .setNegativeButton("Cancel", null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog d = (AlertDialog) getDialog();
        if (d == null) return;
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ContainerKey key = buildKey();
            if (key != null && listener != null) {
                listener.onKeySaved(key);
                dismiss();
            }
        });
    }

    /** Returns the resolved algorithm ID; for OTHER reads from the hex field (-1 if invalid). */
    /** Fill the key-hex field with {@code len} random bytes and clear any stale validation. */
    private void fillRandomKey(int len) {
        byte[] keyBytes = new byte[len];
        new SecureRandom().nextBytes(keyBytes);
        if (DesKeyValidator.isDesAlgorithm(resolveAlgId())) {
            DesKeyValidator.fixParity(keyBytes);
        }
        binding.etKeyHex.setText(bytesToHex(keyBytes));
        validateAll();
    }

    /**
     * Apply an {@link android.text.InputFilter.LengthFilter} to the key-hex field
     * sized to the currently selected algorithm — {@code expectedBytes * 2} hex chars,
     * or 64 (32 bytes) as a fallback for Other/unknown. Trims any over-length content.
     */
    private void applyKeyHexMaxLength() {
        if (binding == null) return;
        int pos = binding.spinnerAlgorithm.getSelectedItemPosition();
        boolean isOther = AlgorithmId.VALUES[pos] == AlgorithmId.OTHER;
        int expectedLen = isOther ? 0 : AlgorithmId.keyLength(resolveAlgId());
        int max = (expectedLen > 0) ? expectedLen * 2 : 64;
        binding.etKeyHex.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(max) });
        android.text.Editable e = binding.etKeyHex.getText();
        if (e != null && e.length() > max) e.delete(max, e.length());
    }

    private int resolveAlgId() {
        int pos = binding.spinnerAlgorithm.getSelectedItemPosition();
        if (AlgorithmId.VALUES[pos] != AlgorithmId.OTHER) return AlgorithmId.VALUES[pos];
        String hex = text(binding.etOtherAlgId).replaceFirst("(?i)^0x", "");
        try {
            int v = Integer.parseInt(hex, 16);
            return (v >= 0 && v <= 0xFF) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private ContainerKey buildKey() {
        String name   = text(binding.etName);
        String keyHex = text(binding.etKeyHex).replaceAll("\\s+", "").toUpperCase();
        int algId     = resolveAlgId();

        if (algId < 0) {
            Toast.makeText(requireContext(), "Enter a valid Algorithm ID (hex 00–FF)", Toast.LENGTH_SHORT).show();
            return null;
        }

        int keysetId, keyId, sln;
        try {
            keysetId = Integer.parseInt(text(binding.etKeysetId));
            keyId    = settings.parseKeyId(text(binding.etKeyId));
            sln      = settings.parseSln(text(binding.etSln));
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "Invalid numeric field", Toast.LENGTH_SHORT).show();
            return null;
        }

        if (keysetId < 1 || keysetId > 255) {
            Toast.makeText(requireContext(), "Keyset ID must be 1–255", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (keyId < 0 || keyId > 65535) {
            Toast.makeText(requireContext(), settings.keyIdRangeError(), Toast.LENGTH_SHORT).show();
            return null;
        }
        if (sln < 0 || sln > 65535) {
            Toast.makeText(requireContext(), settings.slnRangeError(), Toast.LENGTH_SHORT).show();
            return null;
        }
        if (keyHex.isEmpty()) {
            Toast.makeText(requireContext(), "Key hex is required", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (keyHex.length() % 2 != 0) {
            Toast.makeText(requireContext(), "Key hex must have even length", Toast.LENGTH_SHORT).show();
            return null;
        }
        if (!keyHex.matches("[0-9A-Fa-f]+")) {
            Toast.makeText(requireContext(), "Key hex contains invalid characters", Toast.LENGTH_SHORT).show();
            return null;
        }

        boolean isOther = AlgorithmId.VALUES[binding.spinnerAlgorithm.getSelectedItemPosition()] == AlgorithmId.OTHER;
        int expectedLen = AlgorithmId.keyLength(algId);
        if (!isOther && expectedLen > 0 && keyHex.length() / 2 != expectedLen) {
            Toast.makeText(requireContext(),
                    String.format("Expected %d bytes (%d hex chars) for this algorithm",
                            expectedLen, expectedLen * 2), Toast.LENGTH_LONG).show();
            return null;
        }

        if (DesKeyValidator.isDesAlgorithm(algId)) {
            byte[] keyBytes = com.sentinelcomms.kfddroid.container.ContainerIO.hexToBytes(keyHex);
            if (!DesKeyValidator.isValidParity(keyBytes)) {
                Toast.makeText(requireContext(), "Invalid DES key parity", Toast.LENGTH_LONG).show();
                return null;
            }
        }

        ContainerKey key = new ContainerKey();
        if (existing != null) key.id = existing.id; // preserve ID when editing
        key.name        = name.isEmpty() ? "Key " + (keyId) : name;
        key.algorithmId = algId;
        key.keysetId    = keysetId;
        key.keyId       = keyId;
        key.sln         = sln;
        key.keyHex      = keyHex;
        boolean isKek    = isKekSln(sln);
        key.keyTypeKek   = isKek;
        key.keyTypeTek   = !isKek;
        key.keyTypeAuto  = true;
        key.activeKeyset = binding.cbActiveKeyset.isChecked();
        return key;
    }

    // ── Key type detection ────────────────────────────────────────────────────

    /** SLN 61440–65534 (0xF000–0xFFFE) = KEK; SLN 0–61439 = TEK. */
    private static boolean isKekSln(int sln) {
        return sln >= 61440 && sln <= 65534;
    }

    private void updateKeyTypeLabel() {
        if (binding == null) return;
        boolean isKek = false;
        try { isKek = isKekSln(settings.parseSln(text(binding.etSln))); } catch (NumberFormatException ignored) {}
        binding.tvKeyType.setText(isKek ? "Key Type: KEK" : "Key Type: TEK");
    }

    // ── Inline field validators ────────────────────────────────────────────────

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
        if (binding == null) return;
        updateKeyTypeLabel();
        boolean isOther = AlgorithmId.VALUES[
                binding.spinnerAlgorithm.getSelectedItemPosition()] == AlgorithmId.OTHER;
        if (isOther) validateOtherAlgId();
        validateKeysetId();
        validateKeyId();
        validateSln();
        validateKeyHex();
    }

    private void validateOtherAlgId() {
        if (binding == null) return;
        String hex = text(binding.etOtherAlgId).replaceFirst("(?i)^0x", "");
        if (hex.isEmpty()) { setError(binding.tilOtherAlgId, null); return; }
        try {
            int v = Integer.parseInt(hex, 16);
            setError(binding.tilOtherAlgId, (v >= 0 && v <= 0xFF) ? null : "Must be 00–FF");
        } catch (NumberFormatException e) {
            setError(binding.tilOtherAlgId, "Invalid hex");
        }
    }

    private void validateKeysetId() {
        if (binding == null) return;
        String raw = text(binding.etKeysetId);
        if (raw.isEmpty()) { setError(binding.tilKeysetId, null); return; }
        try {
            int v = Integer.parseInt(raw);
            setError(binding.tilKeysetId, (v >= 1 && v <= 255) ? null : "Must be 1–255");
        } catch (NumberFormatException e) {
            setError(binding.tilKeysetId, "Invalid number");
        }
    }

    private void validateKeyId() {
        if (binding == null) return;
        String raw = text(binding.etKeyId);
        if (raw.isEmpty()) { setError(binding.tilKeyId, null); return; }
        try {
            int v = settings.parseKeyId(raw);
            setError(binding.tilKeyId, (v >= 0 && v <= 65535) ? null : settings.keyIdRangeError());
        } catch (NumberFormatException e) {
            setError(binding.tilKeyId, settings.keyIdInvalidError());
        }
    }

    private void validateSln() {
        if (binding == null) return;
        String raw = text(binding.etSln);
        if (raw.isEmpty()) { setError(binding.tilSln, null); return; }
        try {
            int v = settings.parseSln(raw);
            setError(binding.tilSln, (v >= 0 && v <= 65535) ? null : settings.slnRangeError());
        } catch (NumberFormatException e) {
            setError(binding.tilSln, settings.slnInvalidError());
        }
    }

    private void validateKeyHex() {
        if (binding == null) return;
        String hex = text(binding.etKeyHex).replaceAll("\\s+", "").toUpperCase();
        if (hex.isEmpty()) { setError(binding.tilKeyHex, null); return; }
        if (hex.length() % 2 != 0) {
            setError(binding.tilKeyHex, "Must have an even number of hex characters");
            return;
        }
        if (!hex.matches("[0-9A-F]+")) {
            setError(binding.tilKeyHex, "Invalid hex characters");
            return;
        }
        boolean isOther = AlgorithmId.VALUES[binding.spinnerAlgorithm.getSelectedItemPosition()] == AlgorithmId.OTHER;
        int algId = resolveAlgId();
        int expectedLen = AlgorithmId.keyLength(algId);
        if (!isOther && expectedLen > 0 && hex.length() / 2 != expectedLen) {
            setError(binding.tilKeyHex, String.format("Expected %d bytes (%d hex chars) for this algorithm",
                    expectedLen, expectedLen * 2));
            return;
        }
        if (DesKeyValidator.isDesAlgorithm(algId) && hex.length() == 16) {
            if (!DesKeyValidator.isValidParity(
                    com.sentinelcomms.kfddroid.container.ContainerIO.hexToBytes(hex))) {
                setError(binding.tilKeyHex, "Invalid DES key parity");
                return;
            }
        }
        setError(binding.tilKeyHex, null);
    }

    /**
     * Text-change validator: same rules as {@link #validateKeyHex} except
     * "still under expected length" stays silent so the user isn't nagged
     * mid-type. Non-hex characters and "too many" are surfaced immediately.
     */
    private void validateKeyHexLive() {
        if (binding == null) return;
        String hex = text(binding.etKeyHex).replaceAll("\\s+", "").toUpperCase();
        if (hex.isEmpty()) { setError(binding.tilKeyHex, null); return; }
        if (!hex.matches("[0-9A-F]+")) {
            setError(binding.tilKeyHex, "Invalid hex characters");
            return;
        }
        boolean isOther = AlgorithmId.VALUES[binding.spinnerAlgorithm.getSelectedItemPosition()] == AlgorithmId.OTHER;
        int algId = resolveAlgId();
        int expectedLen = AlgorithmId.keyLength(algId);
        if (isOther || expectedLen <= 0) { setError(binding.tilKeyHex, null); return; }
        int expectedChars = expectedLen * 2;
        if (hex.length() > expectedChars) {
            setError(binding.tilKeyHex,
                    String.format("Too long — expected %d hex chars for this algorithm", expectedChars));
            return;
        }
        // At exactly the expected length for a DES algorithm, check parity live.
        if (hex.length() == expectedChars
                && DesKeyValidator.isDesAlgorithm(algId)
                && !DesKeyValidator.isValidParity(
                    com.sentinelcomms.kfddroid.container.ContainerIO.hexToBytes(hex))) {
            setError(binding.tilKeyHex, "Invalid DES key parity");
            return;
        }
        setError(binding.tilKeyHex, null);
    }

    private void setError(com.google.android.material.textfield.TextInputLayout til, String error) {
        if (error != null) {
            til.setError(error);
        } else {
            til.setError(null);
            til.setErrorEnabled(false);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String text(com.google.android.material.textfield.TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
