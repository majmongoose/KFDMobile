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
package com.sentinelcomms.kfdmobile.container;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/** Maps to KFDtool.Container.GroupItem. */
public class ContainerGroup implements Parcelable {
    public int id;
    public String name = "";
    public List<Integer> keys = new ArrayList<>();

    public ContainerGroup() {}

    protected ContainerGroup(Parcel in) {
        id   = in.readInt();
        name = in.readString();
        int n = in.readInt();
        keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) keys.add(in.readInt());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeString(name == null ? "" : name);
        dest.writeInt(keys == null ? 0 : keys.size());
        if (keys != null) for (Integer k : keys) dest.writeInt(k);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<ContainerGroup> CREATOR = new Creator<ContainerGroup>() {
        @Override public ContainerGroup createFromParcel(Parcel in) { return new ContainerGroup(in); }
        @Override public ContainerGroup[] newArray(int size) { return new ContainerGroup[size]; }
    };
}
