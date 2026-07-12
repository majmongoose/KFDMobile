/*
 * Copyright 2026 Charles Bricker — see project LICENSE for full terms.
 */
package com.sentinelcomms.kfddroid.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.sentinelcomms.kfddroid.MainActivity;
import com.sentinelcomms.kfddroid.R;
import com.sentinelcomms.kfddroid.container.ContainerFile;
import com.sentinelcomms.kfddroid.container.ContainerKey;
import com.sentinelcomms.kfddroid.p25.MrEmulatorProtocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocking dialog that runs an MR Emulator session and populates the caller's container.
 *
 * Finish — keeps received keys, stops emulator, dismisses.
 * Cancel — discards received keys (removes them from container), stops emulator, dismisses.
 * TRANSFER_DONE — auto-dismisses (same as Finish).
 */
public class MrEmulatorDialogFragment extends DialogFragment {

    /** Receives keys and can remove them (for cancel). */
    public interface KeySink {
        /** Add a received key; return its assigned ID so it can be removed on cancel. */
        int addKey(int keysetId, int algId, int keyId, int sln, byte[] keyBytes);
        /** Remove keys that were added during the session (cancel path). */
        void removeKeys(List<Integer> keyIds);
    }

    private KeySink keySink;

    private TextView tvStatus;
    private TextView tvCount;
    private View     btnFinish;
    private View     btnCancel;

    private final List<Integer> receivedKeyIds = new ArrayList<>();
    private int keyCount = 0;
    private boolean sessionDone = false;

    public void setKeySink(KeySink sink) {
        this.keySink = sink;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_mr_emulator, null, false);

        tvStatus  = view.findViewById(R.id.tv_mr_status);
        tvCount   = view.findViewById(R.id.tv_mr_count);
        btnFinish = view.findViewById(R.id.btn_mr_finish);
        btnCancel = view.findViewById(R.id.btn_mr_cancel);

        btnFinish.setOnClickListener(v -> finishSession());
        btnCancel.setOnClickListener(v -> cancelSession());

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("MR Emulator")
                .setView(view)
                .create();

        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        startEmulation();
    }

    @Override
    public void onStop() {
        super.onStop();
        // Safety net: stop emulator if dialog is dismissed externally
        ((MainActivity) requireActivity()).stopMrEmulation();
    }

    // ── Emulation control ─────────────────────────────────────────────────────

    private void startEmulation() {
        MainActivity act = (MainActivity) requireActivity();
        act.startMrEmulation(new MrEmulatorProtocol.Listener() {
            @Override public void onStatus(String message) {
                post(() -> setStatus(message));
            }

            @Override public void onKeyReceived(int keysetId, int algId, int keyId,
                                                int sln, boolean erase, byte[] key) {
                post(() -> {
                    if (keySink != null && !erase) {
                        int id = keySink.addKey(keysetId, algId, keyId, sln, key);
                        receivedKeyIds.add(id);
                    }
                    keyCount++;
                    tvCount.setText("Keys received: " + keyCount);
                    if (keyCount == 1) btnFinish.setVisibility(View.VISIBLE);
                });
            }

            @Override public void onSessionStarted() {
                post(() -> setStatus("KFD connected — receiving keys…"));
            }

            @Override public void onSessionEnded() {
                post(() -> {
                    sessionDone = true;
                    setStatus("Transfer complete.");
                    // Auto-dismiss on TRANSFER_DONE
                    dismissAllowingStateLoss();
                });
            }
        });
    }

    private void finishSession() {
        sessionDone = true;
        ((MainActivity) requireActivity()).stopMrEmulation();
        dismissAllowingStateLoss();
    }

    private void cancelSession() {
        ((MainActivity) requireActivity()).stopMrEmulation();
        if (keySink != null && !receivedKeyIds.isEmpty()) {
            keySink.removeKeys(receivedKeyIds);
        }
        dismissAllowingStateLoss();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setStatus(String msg) {
        if (tvStatus != null) tvStatus.setText(msg);
    }

    /** Run on UI thread, guarding against detached state. */
    private void post(Runnable r) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (isAdded()) r.run();
        });
    }
}
