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
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kfdtool.android.AppSettings;
import com.kfdtool.android.R;

public class SettingsDialogFragment extends DialogFragment {

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AppSettings settings = new AppSettings(requireContext());

        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_settings, null);

        SwitchMaterial swKeyIdHex = view.findViewById(R.id.sw_key_id_hex);
        SwitchMaterial swSlnHex   = view.findViewById(R.id.sw_sln_hex);
        TextView tvKeyIdDesc      = view.findViewById(R.id.tv_key_id_format_desc);
        TextView tvSlnDesc        = view.findViewById(R.id.tv_sln_format_desc);

        swKeyIdHex.setChecked(settings.isKeyIdHex());
        swSlnHex.setChecked(settings.isSlnHex());
        updateDesc(tvKeyIdDesc, settings.isKeyIdHex());
        updateDesc(tvSlnDesc,   settings.isSlnHex());

        swKeyIdHex.setOnCheckedChangeListener((btn, checked) -> {
            settings.setKeyIdHex(checked);
            updateDesc(tvKeyIdDesc, checked);
        });
        swSlnHex.setOnCheckedChangeListener((btn, checked) -> {
            settings.setSlnHex(checked);
            updateDesc(tvSlnDesc, checked);
        });

        return new AlertDialog.Builder(requireContext())
                .setTitle("Settings")
                .setView(view)
                .setPositiveButton("Done", null)
                .create();
    }

    private static void updateDesc(TextView tv, boolean hex) {
        tv.setText(hex ? "Hexadecimal (e.g. 0x1A2B)" : "Decimal (e.g. 6699)");
    }
}
