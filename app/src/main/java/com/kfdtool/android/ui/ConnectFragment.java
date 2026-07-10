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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;
import com.kfdtool.android.MainActivity;
import com.kfdtool.android.R;
import com.kfdtool.android.databinding.FragmentConnectBinding;

/**
 * Shows connection state and provides Connect/Disconnect (TWI) or Configure/Test (DLI).
 * Mode and DLI settings are persisted in SharedPreferences across app restarts.
 */
public class ConnectFragment extends Fragment {

    private static final String PREFS_NAME   = "connect_prefs";
    private static final String KEY_DLI_HOST = "dli_host";
    private static final String KEY_DLI_PORT = "dli_port";
    private static final String KEY_DLI_MOT  = "dli_motorola";

    private FragmentConnectBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentConnectBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        SharedPreferences prefs = prefs();
        String  dliHost = prefs.getString(KEY_DLI_HOST, "192.168.132.1");
        int     dliPort = prefs.getInt(KEY_DLI_PORT, 49644);
        boolean dliMot  = prefs.getBoolean(KEY_DLI_MOT, true);

        // Always start in TWI/KFD mode; restore DLI config silently so Configure is pre-filled
        binding.toggleMode.check(R.id.btn_mode_twi);
        applyMode(false);

        // Mode toggle
        binding.toggleMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean isDli = (checkedId == R.id.btn_mode_dli);
            if (isDli) {
                MainActivity act = (MainActivity) requireActivity();
                act.setDliConfig(dliHost, dliPort, dliMot);
                applyMode(true);
                act.autoTestDli();
            } else {
                ((MainActivity) requireActivity()).clearDli();
                applyMode(false);
            }
        });

        // TWI buttons
        binding.btnConnect.setOnClickListener(v ->
                ((MainActivity) requireActivity()).connectToUsb());
        binding.btnDisconnect.setOnClickListener(v ->
                ((MainActivity) requireActivity()).disconnectUsb());

        // DLI buttons
        binding.btnDliConfigure.setOnClickListener(v -> showConfigureDialog());
        binding.btnDliTest.setOnClickListener(v -> testDliConnection());

        refreshState();
    }

    // ── Mode switching ────────────────────────────────────────────────────────

    private void applyMode(boolean dliMode) {
        if (binding == null) return;
        binding.sectionTwi.setVisibility(dliMode ? View.GONE  : View.VISIBLE);
        binding.sectionDli.setVisibility(dliMode ? View.VISIBLE : View.GONE);
        refreshState();
    }

    private boolean isDliMode() {
        if (binding == null) return false;
        return binding.toggleMode.getCheckedButtonId() == R.id.btn_mode_dli;
    }

    // ── DLI configure dialog ──────────────────────────────────────────────────

    private void showConfigureDialog() {
        SharedPreferences prefs = prefs();
        String  savedHost = prefs.getString(KEY_DLI_HOST, "192.168.132.1");
        int     savedPort = prefs.getInt(KEY_DLI_PORT, 49644);
        boolean savedMot  = prefs.getBoolean(KEY_DLI_MOT, true);

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_dli_config, null, false);

        TextInputEditText etHost = dialogView.findViewById(R.id.et_dli_host);
        TextInputEditText etPort = dialogView.findViewById(R.id.et_dli_port);
        CheckBox          cbMot  = dialogView.findViewById(R.id.cb_dli_motorola);

        if (!savedHost.isEmpty()) etHost.setText(savedHost);
        etPort.setText(String.valueOf(savedPort));
        cbMot.setChecked(savedMot);

        dialogView.findViewById(R.id.btn_open_bt_settings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));

        // Use null for the positive button so we can override click to prevent
        // automatic dismiss on validation failure
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("DLI Configuration")
                .setView(dialogView)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String host = etHost.getText() != null
                            ? etHost.getText().toString().trim() : "";
                    String portStr = etPort.getText() != null
                            ? etPort.getText().toString().trim() : "";
                    boolean motVariant = cbMot.isChecked();

                    if (host.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "Enter an IP address or hostname", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int port;
                    try {
                        port = Integer.parseInt(portStr);
                        if (port < 1 || port > 65535) throw new NumberFormatException();
                    } catch (NumberFormatException e) {
                        Toast.makeText(requireContext(),
                                "Port must be 1–65535", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Persist and activate
                    prefs().edit()
                            .putString(KEY_DLI_HOST, host)
                            .putInt(KEY_DLI_PORT, port)
                            .putBoolean(KEY_DLI_MOT, motVariant)
                            .apply();

                    ((MainActivity) requireActivity()).setDliConfig(host, port, motVariant);
                    refreshState();
                    appendLog("DLI configured: " + host + ":" + port
                            + (motVariant ? " (Motorola)" : " (Standard)"));
                    dialog.dismiss();
                }));

        dialog.show();
    }

    // ── DLI test ──────────────────────────────────────────────────────────────

    private void testDliConnection() {
        MainActivity act = (MainActivity) requireActivity();
        if (!act.isDliActive()) {
            appendLog("Not configured — tap Configure to set up DLI.");
            return;
        }

        binding.btnDliTest.setEnabled(false);
        binding.btnDliTest.setText("Testing…");

        act.testDliConnection((success, message, debugLog) ->
            act.runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                appendLog(success ? "DLI: Connected." : "DLI: " + message);
                binding.btnDliTest.setText(getString(R.string.btn_dli_test));
                binding.btnDliTest.setEnabled(true);
            })
        );
    }

    // ── State refresh ─────────────────────────────────────────────────────────

    public void refreshState() {
        if (binding == null) return;
        MainActivity act = (MainActivity) requireActivity();

        if (isDliMode()) {
            if (act.isDliVerified()) {
                binding.tvConnectionStatus.setText(getString(R.string.label_dli_ready));
                binding.cardStatus.setCardBackgroundColor(
                        getResources().getColor(R.color.colorConnected, null));
            } else if (act.isDliFailed()) {
                binding.tvConnectionStatus.setText(getString(R.string.label_dli_failed));
                binding.cardStatus.setCardBackgroundColor(0xFFB71C1C);
            } else {
                binding.tvConnectionStatus.setText(getString(R.string.label_dli_not_connected));
                binding.cardStatus.setCardBackgroundColor(0xFF455A64);
            }
            binding.tvDeviceName.setVisibility(View.GONE);
        } else {
            boolean usbOpen = act.isUsbConnected();
            boolean ready   = act.isAdapterReady();
            if (ready) {
                binding.tvConnectionStatus.setText(getString(R.string.label_connected));
                binding.cardStatus.setCardBackgroundColor(
                        getResources().getColor(R.color.colorConnected, null));
            } else {
                binding.tvConnectionStatus.setText(usbOpen
                        ? getString(R.string.label_connecting)
                        : getString(R.string.label_no_device));
                binding.cardStatus.setCardBackgroundColor(0xFF455A64);
            }
            binding.btnConnect.setEnabled(!usbOpen);
            binding.btnDisconnect.setEnabled(usbOpen);
        }
    }

    public void appendLog(String text) {
        if (binding == null) return;
        requireActivity().runOnUiThread(() -> {
            if (binding == null) return;
            String current = binding.tvLog.getText().toString();
            binding.tvLog.setText(current.isEmpty() ? text : current + "\n" + text);
            binding.scrollLog.post(() -> {
                if (binding != null) binding.scrollLog.fullScroll(View.FOCUS_DOWN);
            });
        });
    }

    public void setDeviceName(String name) {
        if (binding == null) return;
        requireActivity().runOnUiThread(() -> {
            if (binding == null) return;
            if (name != null) {
                binding.tvDeviceName.setVisibility(View.VISIBLE);
                binding.tvDeviceName.setText(name);
            } else {
                binding.tvDeviceName.setVisibility(View.GONE);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Replace the test section of the log without duplicating it on each debug line. */
    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
