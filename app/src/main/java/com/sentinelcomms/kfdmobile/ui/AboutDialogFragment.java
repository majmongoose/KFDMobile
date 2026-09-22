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

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.sentinelcomms.kfdmobile.BuildConfig;
import com.sentinelcomms.kfdmobile.MainActivity;
import com.sentinelcomms.kfdmobile.databinding.DialogAboutBinding;

public class AboutDialogFragment extends DialogFragment {

    private DialogAboutBinding binding;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogAboutBinding.inflate(LayoutInflater.from(requireContext()));

        binding.tvAboutVersion.setText("KFDMobile v" + BuildConfig.VERSION_NAME);

        MainActivity act = (MainActivity) requireActivity();

        if (act.isUsbConnected()) {
            binding.sectionKeyloader.setVisibility(View.VISIBLE);
            binding.tvNoKeyloader.setVisibility(View.GONE);
            binding.btnReadDeviceInfo.setOnClickListener(v -> readDeviceInfo(act));
        } else {
            binding.sectionKeyloader.setVisibility(View.GONE);
            binding.tvNoKeyloader.setVisibility(View.VISIBLE);
        }

        return new AlertDialog.Builder(requireContext())
                .setTitle("About")
                .setView(binding.getRoot())
                .setPositiveButton("Close", null)
                .create();
    }

    private void readDeviceInfo(MainActivity act) {
        binding.btnReadDeviceInfo.setEnabled(false);

        act.readDeviceInfo(info -> act.runOnUiThread(() -> {
            if (!isAdded() || binding == null) return;
            binding.btnReadDeviceInfo.setEnabled(true);
            if (info != null) {
                binding.tvApVersion.setText(info.apVersion   != null ? info.apVersion   : "—");
                binding.tvFwVersion.setText(info.fwVersion   != null ? info.fwVersion   : "—");
                binding.tvModelId.setText(info.modelId       != null ? info.modelId     : "—");
                binding.tvHwRevision.setText(info.hwRevision != null ? info.hwRevision  : "—");
                binding.tvSerialNumber.setText(info.serialNumber != null ? info.serialNumber : "—");
                binding.tvUniqueId.setText(info.uniqueId     != null ? info.uniqueId    : "—");
            } else {
                binding.tvApVersion.setText("Read failed — check connection.");
                binding.tvFwVersion.setText("—");
                binding.tvModelId.setText("—");
                binding.tvHwRevision.setText("—");
                binding.tvSerialNumber.setText("—");
                binding.tvUniqueId.setText("—");
            }
        }));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
