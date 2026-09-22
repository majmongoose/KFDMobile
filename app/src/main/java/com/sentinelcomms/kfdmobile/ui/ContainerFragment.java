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
package com.sentinelcomms.kfdmobile.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.sentinelcomms.kfdmobile.AppSettings;
import com.sentinelcomms.kfdmobile.MainActivity;
import com.sentinelcomms.kfdmobile.R;
import com.sentinelcomms.kfdmobile.container.ContainerFile;
import com.sentinelcomms.kfdmobile.container.ContainerIO;
import com.sentinelcomms.kfdmobile.container.ContainerKey;
import com.sentinelcomms.kfdmobile.databinding.FragmentContainerBinding;
import com.sentinelcomms.kfdmobile.databinding.ItemContainerKeyBinding;
import com.sentinelcomms.kfdmobile.model.AlgorithmId;
import com.sentinelcomms.kfdmobile.model.CmdKeyItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Container management: create / open / edit / save .ekc files and load keys to radio.
 *
 * Long-press a key card to enter multi-select mode; tap cards to toggle selection.
 * "Load to Radio" loads all TEK keys when nothing is selected, or only the selected
 * TEK keys when in selection mode.
 */
public class ContainerFragment extends Fragment {

    private FragmentContainerBinding binding;
    private ContainerFile openContainer;
    private Uri           openUri;
    private String        openPassword;

    private KeyAdapter adapter;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private boolean isDirty = false;
    private AppSettings settings;

    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (prefs, key) -> {
                if (AppSettings.KEY_ID_HEX.equals(key) || AppSettings.SLN_HEX.equals(key)) {
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
            };

    // Multi-select state (keyed by ContainerKey.id)
    private final Set<Integer> selectedKeyIds = new HashSet<>();
    private boolean selectionMode = false;

    // ─── File picker launchers ──────────────────────────────────────────────────

    // Set before launching saveAsFileLauncher when a follow-up action should run after save.
    private Runnable pendingPostSave = null;

    private final ActivityResultLauncher<Intent> openFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) { promptPasswordThenOpen(uri); return; }
                }
                ensureContainer(); // user cancelled picker — restore blank if nothing is open
            });

    private final ActivityResultLauncher<Intent> saveAsFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        Runnable post = pendingPostSave;
                        pendingPostSave = null;
                        ensurePasswordThenSave(uri, post);
                        return;
                    }
                }
                // User cancelled save-as — discard any pending chained action
                pendingPostSave = null;
                ensureContainer();
            });

    // ─── Lifecycle ──────────────────────────────────────────────────────────────

    // Instance-state keys. Bundle is process-local RAM only; not persisted to disk.
    private static final String STATE_CONTAINER      = "container";
    private static final String STATE_URI            = "uri";
    private static final String STATE_PASSWORD       = "password";
    private static final String STATE_DIRTY          = "dirty";
    private static final String STATE_SELECTED_IDS   = "selected_ids";
    private static final String STATE_SELECTION_MODE = "selection_mode";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
        binding = FragmentContainerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        settings = new AppSettings(requireContext());
        settings.getPrefs().registerOnSharedPreferenceChangeListener(settingsListener);

        adapter = new KeyAdapter();
        binding.rvKeys.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvKeys.setAdapter(adapter);

        binding.btnNewContainer.setOnClickListener(v -> openOrPromptSave(() -> {
            openContainer = ContainerIO.newContainer();
            openUri       = null;
            openPassword  = null;
            isDirty       = false;
            clearSelection();
            refreshUi();
        }));
        binding.btnOpenContainer.setOnClickListener(v -> openOrPromptSave(this::launchOpenPicker));
        binding.btnSaveContainer.setOnClickListener(v -> {
            if (openUri != null) ensurePasswordThenSave(openUri, null);
            else launchSaveAsPicker();
        });
        binding.btnSaveAsContainer.setOnClickListener(v -> launchSaveAsPicker());
        binding.btnAddKey.setOnClickListener(v -> showKeyEditDialog(null));
        binding.btnLoadToRadio.setOnClickListener(v -> loadToRadio());
        binding.btnMrEmulator.setOnClickListener(v -> launchMrEmulator());

        if (savedInstanceState != null) restoreState(savedInstanceState);
        ensureContainer();
        refreshUi();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        // Save the open container, its URI, password, dirty flag, and selection state
        // so rotation/aspect-ratio config changes don't drop the user's work.
        if (openContainer != null) out.putParcelable(STATE_CONTAINER, openContainer);
        if (openUri       != null) out.putParcelable(STATE_URI, openUri);
        if (openPassword  != null) out.putString(STATE_PASSWORD, openPassword);
        out.putBoolean(STATE_DIRTY, isDirty);
        out.putBoolean(STATE_SELECTION_MODE, selectionMode);
        int[] ids = new int[selectedKeyIds.size()];
        int i = 0;
        for (Integer id : selectedKeyIds) ids[i++] = id;
        out.putIntArray(STATE_SELECTED_IDS, ids);
    }

    private void restoreState(@NonNull Bundle in) {
        openContainer = in.getParcelable(STATE_CONTAINER);
        openUri       = in.getParcelable(STATE_URI);
        openPassword  = in.getString(STATE_PASSWORD);
        isDirty       = in.getBoolean(STATE_DIRTY, false);
        selectionMode = in.getBoolean(STATE_SELECTION_MODE, false);
        selectedKeyIds.clear();
        int[] ids = in.getIntArray(STATE_SELECTED_IDS);
        if (ids != null) for (int id : ids) selectedKeyIds.add(id);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateLoadButton(); // refresh connection-dependent button state when tab is re-entered
        android.net.Uri uri = ((MainActivity) requireActivity()).consumePendingOpenUri();
        if (uri != null) promptPasswordThenOpen(uri);
    }

    public void onDestroyView() {
        super.onDestroyView();
        if (settings != null) settings.getPrefs().unregisterOnSharedPreferenceChangeListener(settingsListener);
        binding = null;
        // Drop plaintext key material references as early as possible on the outgoing
        // fragment instance. State restore uses the parceled Bundle, not these fields.
        openContainer = null;
        openPassword  = null;
        selectedKeyIds.clear();
    }

    // ─── Container operations ────────────────────────────────────────────────────

    /** Ensures there is always at least a blank container open. */
    private void ensureContainer() {
        if (openContainer == null) {
            openContainer = ContainerIO.newContainer();
            openUri       = null;
            openPassword  = null;
            refreshUi();
        }
    }

    /**
     * If the current container has meaningful content (keys or a saved file), shows a
     * Save / Discard / Cancel dialog before running {@code proceed}. Otherwise runs
     * {@code proceed} immediately.
     */
    private void openOrPromptSave(Runnable proceed) {
        if (!isDirty) {
            proceed.run();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Current Container")
                .setMessage("Save or discard the current container before continuing?")
                .setPositiveButton("Save",    (d, w) -> saveAndThenRun(proceed))
                .setNeutralButton("Discard",  (d, w) -> { closeContainer(); proceed.run(); })
                .setNegativeButton("Cancel",  null)
                .show();
    }

    /**
     * Saves the current container (in place if it has a URI, otherwise via Save As picker),
     * then runs {@code proceed} after a successful save.
     */
    private void saveAndThenRun(Runnable proceed) {
        if (openUri != null) {
            ensurePasswordThenSave(openUri, () -> { closeContainer(); proceed.run(); });
        } else {
            // No save location yet — launch Save As; proceed will be chained via pendingPostSave
            pendingPostSave = () -> { closeContainer(); proceed.run(); };
            launchSaveAsPicker();
        }
    }

    /**
     * If no password is set, prompts the user to create one, then saves to {@code uri}.
     * Calls {@code onSuccess} on the UI thread after a successful save (may be null).
     */
    private void ensurePasswordThenSave(Uri uri, @Nullable Runnable onSuccess) {
        if (openPassword != null) {
            saveToUri(uri, onSuccess);
            return;
        }

        EditText pwdInput = new EditText(requireContext());
        pwdInput.setHint("Set password");
        pwdInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwdInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);

        EditText confirmInput = new EditText(requireContext());
        confirmInput.setHint("Confirm password");
        confirmInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);

        android.widget.LinearLayout ll = new android.widget.LinearLayout(requireContext());
        ll.setOrientation(android.widget.LinearLayout.VERTICAL);
        ll.setPadding(48, 16, 48, 0);
        ll.addView(pwdInput);
        ll.addView(confirmInput);

        AlertDialog pwdDialog = new AlertDialog.Builder(requireContext())
                .setTitle("Set Container Password")
                .setView(ll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        pwdDialog.setOnShowListener(d -> {
            pwdInput.requestFocus();
            pwdDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String pwd  = pwdInput.getText().toString();
                String conf = confirmInput.getText().toString();
                if (pwd.isEmpty()) {
                    Toast.makeText(requireContext(), "Password cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!pwd.equals(conf)) {
                    Toast.makeText(requireContext(), "Passwords do not match", Toast.LENGTH_SHORT).show();
                    return;
                }
                openPassword = pwd;
                pwdDialog.dismiss();
                saveToUri(uri, onSuccess);
            });
        });
        pwdDialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        pwdDialog.show();
    }

    private void closeContainer() {
        openContainer = null;
        openUri       = null;
        openPassword  = null;
        isDirty       = false;
        clearSelection();
        refreshUi();
    }

    private void launchOpenPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            openFileLauncher.launch(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "No file manager app found", Toast.LENGTH_LONG).show();
        }
    }

    private void launchSaveAsPicker() {
        if (openContainer == null) {
            Toast.makeText(requireContext(), "No container open", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "keys.ekc");
        try {
            saveAsFileLauncher.launch(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "No file manager app found", Toast.LENGTH_LONG).show();
        }
    }

    private void promptPasswordThenOpen(Uri uri) {
        EditText pwdInput = new EditText(requireContext());
        pwdInput.setHint("Container password");
        pwdInput.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwdInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        android.widget.LinearLayout ll = new android.widget.LinearLayout(requireContext());
        ll.setPadding(48, 16, 48, 0);
        ll.addView(pwdInput);

        AlertDialog openDialog = new AlertDialog.Builder(requireContext())
                .setTitle("Open Container")
                .setView(ll)
                .setPositiveButton("Open", (dialog, which) -> {
                    String pwd = pwdInput.getText().toString();
                    openContainerFromUri(uri, pwd);
                })
                .setNegativeButton("Cancel", (d, w) -> ensureContainer())
                .create();
        openDialog.setOnShowListener(d -> pwdInput.requestFocus());
        openDialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        openDialog.show();
    }

    private void openContainerFromUri(Uri uri, String password) {
        setUiBusy(true);
        ioExecutor.execute(() -> {
            try {
                ContainerFile cf = ContainerIO.openContainer(requireContext(), uri, password);
                requireActivity().runOnUiThread(() -> {
                    openContainer = cf;
                    openUri       = uri;
                    openPassword  = password;
                    isDirty       = false;
                    setUiBusy(false);
                    clearSelection();
                    refreshUi();
                    Toast.makeText(requireContext(), "Opened — " + cf.keys.size() + " key(s)", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    setUiBusy(false);
                    ensureContainer();
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Open Failed")
                            .setMessage(e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void saveToUri(Uri uri, @Nullable Runnable onSuccess) {
        if (openContainer == null || openPassword == null) return;
        setUiBusy(true);
        ioExecutor.execute(() -> {
            try {
                ContainerIO.saveContainer(requireContext(), uri, openContainer, openPassword);
                requireActivity().runOnUiThread(() -> {
                    openUri = uri; // commit only after successful write
                    isDirty = false;
                    setUiBusy(false);
                    refreshUi();
                    Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show();
                    if (onSuccess != null) onSuccess.run();
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    setUiBusy(false);
                    Toast.makeText(requireContext(), "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ─── Key edit ───────────────────────────────────────────────────────────────

    private void showKeyEditDialog(@Nullable ContainerKey existing) {
        KeyEditDialogFragment dialog = existing == null
                ? KeyEditDialogFragment.newKey()
                : KeyEditDialogFragment.editKey(existing);

        dialog.setListener(key -> {
            if (existing == null) {
                openContainer.addKey(key);
            } else {
                openContainer.replaceKey(key);
            }
            isDirty = true;
            adapter.notifyDataSetChanged();
            refreshUi();
        });
        dialog.show(getChildFragmentManager(), "key_edit");
    }

    // ─── Selection ──────────────────────────────────────────────────────────────

    private void enterSelectionMode(int keyId) {
        selectionMode = true;
        selectedKeyIds.add(keyId);
        adapter.notifyDataSetChanged();
        updateLoadButton();
    }

    private void toggleSelection(int keyId) {
        if (selectedKeyIds.contains(keyId)) {
            selectedKeyIds.remove(keyId);
        } else {
            selectedKeyIds.add(keyId);
        }
        if (selectedKeyIds.isEmpty()) {
            selectionMode = false;
        }
        adapter.notifyDataSetChanged();
        updateLoadButton();
    }

    private void clearSelection() {
        selectedKeyIds.clear();
        selectionMode = false;
    }

    // ─── MR Emulator ────────────────────────────────────────────────────────────

    private void launchMrEmulator() {
        MainActivity act = (MainActivity) requireActivity();
        if (!act.isUsbConnected()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("No KFD Adapter Connected")
                    .setMessage("Connect a KFD adapter via USB before using MR Emulator.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        if (openContainer == null) {
            openContainer = ContainerIO.newContainer();
            openUri       = null;
            openPassword  = null;
            isDirty       = false;
            refreshUi();
        }

        MrEmulatorDialogFragment dialog = new MrEmulatorDialogFragment();
        dialog.setKeySink(new MrEmulatorDialogFragment.KeySink() {
            @Override
            public int addKey(int keysetId, int algId, int keyId, int sln, byte[] keyBytes) {
                ContainerKey ck = new ContainerKey();
                ck.algorithmId = algId;
                ck.keysetId    = keysetId;
                ck.keyId       = keyId;
                ck.sln         = sln;
                ck.keyHex      = bytesToHex(keyBytes);
                openContainer.addKey(ck);
                isDirty = true;
                adapter.notifyDataSetChanged();
                refreshUi();
                return ck.id;
            }

            @Override
            public void removeKeys(List<Integer> keyIds) {
                for (int id : keyIds) openContainer.removeKey(id);
                isDirty = true;
                adapter.notifyDataSetChanged();
                refreshUi();
            }
        });
        dialog.show(getChildFragmentManager(), "mr_emulator");
    }

    // ─── Load to radio ──────────────────────────────────────────────────────────

    private void loadToRadio() {
        if (openContainer == null || openContainer.keys.isEmpty()) {
            Toast.makeText(requireContext(), "No keys in container", Toast.LENGTH_SHORT).show();
            return;
        }

        List<CmdKeyItem> items = new ArrayList<>();
        if (selectionMode && !selectedKeyIds.isEmpty()) {
            for (ContainerKey ck : openContainer.keys) {
                if (selectedKeyIds.contains(ck.id) && !ck.keyTypeKek) {
                    items.add(new CmdKeyItem(ck.algorithmId, ck.keysetId, ck.keyId, ck.sln,
                            ContainerIO.hexToBytes(ck.keyHex)));
                }
            }
            if (items.isEmpty()) {
                Toast.makeText(requireContext(), "Selected keys are all KEK — nothing to load", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            items = ContainerIO.toCmdKeyItems(openContainer);
            if (items.isEmpty()) {
                Toast.makeText(requireContext(), "No TEK keys to load (KEK-only container?)", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        final List<CmdKeyItem> toLoad = items;
        new AlertDialog.Builder(requireContext())
                .setTitle("Load " + toLoad.size() + " key(s) to radio?")
                .setMessage("Make sure the radio is connected via KFD adapter before proceeding.")
                .setPositiveButton("Load", (d, w) -> {
                    MainActivity act = (MainActivity) requireActivity();
                    binding.btnLoadToRadio.setEnabled(false);
                    act.performKeyload(toLoad, (success, message, debugLog) -> {
                        if (binding == null) return;
                        requireActivity().runOnUiThread(() -> {
                            if (binding == null) return;
                            updateLoadButton();
                            if (success) {
                                new AlertDialog.Builder(requireContext())
                                        .setTitle("Keyload Complete")
                                        .setMessage(message)
                                        .setPositiveButton("OK", null)
                                        .show();
                            } else if (debugLog != null) {
                                // Failure with debug info — offer Details button
                                final String log = debugLog;
                                new AlertDialog.Builder(requireContext())
                                        .setTitle("Keyload Failed")
                                        .setMessage(message)
                                        .setPositiveButton("OK", null)
                                        .setNeutralButton("Debug Log", (d2, w2) ->
                                                new AlertDialog.Builder(requireContext())
                                                        .setTitle("Debug Log")
                                                        .setMessage(log)
                                                        .setPositiveButton("OK", null)
                                                        .show())
                                        .show();
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────────

    private void refreshUi() {
        if (binding == null) return;
        boolean hasContainer = openContainer != null;

        binding.btnAddKey.setEnabled(hasContainer);
        binding.btnSaveContainer.setEnabled(hasContainer);
        binding.btnSaveAsContainer.setEnabled(hasContainer);

        if (hasContainer) {
            String label;
            if (openUri != null) {
                DocumentFile df = DocumentFile.fromSingleUri(requireContext(), openUri);
                String name = df != null ? df.getName() : null;
                label = name != null ? name : openUri.getLastPathSegment();
            } else {
                label = "New container";
            }
            if (isDirty || openUri == null) label = label + " (unsaved)";
            binding.tvContainerName.setText(label + " — " + openContainer.keys.size() + " key(s)");
        } else {
            binding.tvContainerName.setText("No container open");
        }

        // MR Emulator: show when no container or when container is empty (no keys yet)
        boolean showMr = openContainer == null || openContainer.keys.isEmpty();
        binding.btnMrEmulator.setVisibility(showMr ? View.VISIBLE : View.GONE);

        updateLoadButton();
        adapter.notifyDataSetChanged();
    }

    public void updateLoadButton() {
        if (binding == null) return;
        boolean hasContainer = openContainer != null && !openContainer.keys.isEmpty();
        boolean connected    = ((MainActivity) requireActivity()).isConnected();
        if (selectionMode && !selectedKeyIds.isEmpty()) {
            binding.btnLoadToRadio.setText("Load " + selectedKeyIds.size() + " Selected");
            binding.btnLoadToRadio.setEnabled(connected);
        } else {
            binding.btnLoadToRadio.setText("Load to Radio");
            binding.btnLoadToRadio.setEnabled(hasContainer && connected);
        }
    }

    private void setUiBusy(boolean busy) {
        if (binding == null) return;
        binding.progressLoading.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.btnNewContainer.setEnabled(!busy);
        binding.btnOpenContainer.setEnabled(!busy);
        binding.btnSaveContainer.setEnabled(!busy);
        binding.btnSaveAsContainer.setEnabled(!busy);
        binding.btnAddKey.setEnabled(!busy);
        binding.btnLoadToRadio.setEnabled(!busy);
    }

    private static String bytesToHex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    // ─── RecyclerView Adapter ────────────────────────────────────────────────────

    private class KeyAdapter extends RecyclerView.Adapter<KeyAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final ItemContainerKeyBinding b;
            VH(ItemContainerKeyBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemContainerKeyBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            if (openContainer == null) return;
            ContainerKey key = openContainer.keys.get(position);

            holder.b.tvKeyName.setText(key.name.isEmpty() ? "(unnamed)" : key.name);

            String algName   = getAlgName(key.algorithmId);
            String type      = key.keyTypeKek ? "KEK" : "TEK";
            String keyIdStr  = settings.formatKeyId(key.keyId);
            String slnStr    = settings.formatSln(key.sln);
            holder.b.tvKeyDetails.setText(
                    String.format("AlgID: %s | KsetID: %d | KeyID: %s | SLN: %s | %s",
                            algName, key.keysetId, keyIdStr, slnStr, type));

            MaterialCardView card = (MaterialCardView) holder.itemView;

            if (selectionMode) {
                // In selection mode: hide edit/delete, show selection highlight, tap toggles
                holder.b.btnEditKey.setVisibility(View.GONE);
                holder.b.btnDeleteKey.setVisibility(View.GONE);

                boolean selected = selectedKeyIds.contains(key.id);
                card.setStrokeWidth(selected ? dpToPx(3) : 0);
                card.setStrokeColor(MaterialColors.getColor(card,
                        com.google.android.material.R.attr.colorPrimary));
                card.setClickable(true);
                card.setLongClickable(false);
                card.setOnClickListener(v -> toggleSelection(key.id));
                card.setOnLongClickListener(null);
            } else {
                // Normal mode: show edit/delete, no selection highlight, long-press to start
                holder.b.btnEditKey.setVisibility(View.VISIBLE);
                holder.b.btnDeleteKey.setVisibility(View.VISIBLE);

                card.setStrokeWidth(0);
                card.setClickable(false);
                card.setLongClickable(true);
                card.setOnClickListener(null);
                card.setOnLongClickListener(v -> {
                    enterSelectionMode(key.id);
                    return true;
                });

                holder.b.btnEditKey.setOnClickListener(v -> showKeyEditDialog(key));
                holder.b.btnDeleteKey.setOnClickListener(v ->
                        new AlertDialog.Builder(requireContext())
                                .setTitle("Delete key?")
                                .setMessage("Delete \"" + key.name + "\"?")
                                .setPositiveButton("Delete", (d, w) -> {
                                    selectedKeyIds.remove(key.id);
                                    if (selectedKeyIds.isEmpty()) selectionMode = false;
                                    openContainer.removeKey(key.id);
                                    isDirty = true;
                                    notifyDataSetChanged();
                                    refreshUi();
                                })
                                .setNegativeButton("Cancel", null)
                                .show());
            }
        }

        @Override
        public int getItemCount() {
            return openContainer != null ? openContainer.keys.size() : 0;
        }

        private String getAlgName(int algId) {
            for (int i = 0; i < AlgorithmId.VALUES.length; i++) {
                if (AlgorithmId.VALUES[i] == algId) {
                    String full = AlgorithmId.DISPLAY_NAMES[i];
                    int sp = full.indexOf(' ');
                    return sp > 0 ? full.substring(0, sp) : full;
                }
            }
            return String.format("0x%02X", algId);
        }

        private int dpToPx(int dp) {
            return Math.round(dp * requireContext().getResources().getDisplayMetrics().density);
        }
    }
}
