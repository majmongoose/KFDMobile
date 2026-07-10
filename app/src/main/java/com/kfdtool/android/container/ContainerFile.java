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
package com.kfdtool.android.container;

import java.util.ArrayList;
import java.util.List;

/** In-memory representation of an open .ekc container file. */
public class ContainerFile {
    public List<ContainerKey>   keys   = new ArrayList<>();
    public List<ContainerGroup> groups = new ArrayList<>();
    public int nextKeyNumber   = 1;
    public int nextGroupNumber = 1;

    public void addKey(ContainerKey key) {
        key.id = nextKeyNumber++;
        keys.add(key);
    }

    public void replaceKey(ContainerKey updated) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).id == updated.id) {
                keys.set(i, updated);
                return;
            }
        }
    }

    public void removeKey(int id) {
        keys.removeIf(k -> k.id == id);
    }
}
