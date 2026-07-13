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
package com.sentinelcomms.kfddroid.container;

import android.os.Parcel;
import android.os.Parcelable;

/** Maps to KFDtool.Container.KeyItem. Key field is a hex string (no spaces/dashes). */
public class ContainerKey implements Parcelable {
    public int id;
    public String name = "";
    public int algorithmId;
    public int keysetId;
    public int sln;
    public int keyId;
    public String keyHex = "";       // uppercase hex, e.g. "0102AABB..."
    public boolean activeKeyset = true;
    public boolean keyTypeAuto  = false;
    public boolean keyTypeTek   = true;
    public boolean keyTypeKek   = false;

    public ContainerKey() {}

    protected ContainerKey(Parcel in) {
        id           = in.readInt();
        name         = in.readString();
        algorithmId  = in.readInt();
        keysetId     = in.readInt();
        sln          = in.readInt();
        keyId        = in.readInt();
        keyHex       = in.readString();
        boolean[] bs = new boolean[4];
        in.readBooleanArray(bs);
        activeKeyset = bs[0];
        keyTypeAuto  = bs[1];
        keyTypeTek   = bs[2];
        keyTypeKek   = bs[3];
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeString(name == null ? "" : name);
        dest.writeInt(algorithmId);
        dest.writeInt(keysetId);
        dest.writeInt(sln);
        dest.writeInt(keyId);
        dest.writeString(keyHex == null ? "" : keyHex);
        dest.writeBooleanArray(new boolean[] {
                activeKeyset, keyTypeAuto, keyTypeTek, keyTypeKek });
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<ContainerKey> CREATOR = new Creator<ContainerKey>() {
        @Override public ContainerKey createFromParcel(Parcel in) { return new ContainerKey(in); }
        @Override public ContainerKey[] newArray(int size) { return new ContainerKey[size]; }
    };
}
