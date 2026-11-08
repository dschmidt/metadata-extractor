/*
 * Copyright 2002-2019 Drew Noakes and contributors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 * More information about this project is available at:
 *
 *    https://github.com/drewnoakes/metadata-extractor/
 *    https://github.com/drewnoakes/metadata-extractor
 */
package com.drew.metadata.mov.atoms;

import com.drew.imaging.isobmff.IsoBox;
import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;

import java.io.IOException;

/**
 * https://developer.apple.com/library/content/documentation/QuickTime/QTFF/QTFFChap1/qtff1.html#//apple_ref/doc/uid/TP40000939-CH203-38190
 *
 * @author Payton Garland
 * @deprecated the readers of {@link com.drew.imaging.mp4.Mp4MetadataReader} read this atom into the directories of {@code com.drew.metadata.mp4}.
 */
@Deprecated
public class Atom
{
    public long size;
    public String type;
    /** The number of bytes read for this atom's size and type, 16 for an extended size. */
    public int headerSize = 8;

    public Atom(SequentialReader reader) throws IOException
    {
        this.size = reader.getUInt32();

        /*
         * A zero size isn't legal for contained atoms, but Canon uses it to
         * terminate the CNTH atom (EOS 100D, PowerShot SX30 IS)
         */
        if (size == 0) {
            // previous Atom was terminated by a 32bit zero block
            size = reader.getUInt32();
        }

        this.type = reader.getString(4);
        if (size == 1) {
            size = reader.getInt64();
            headerSize = 16;
        } else if (size == 0) {
            size = -1;
        }
    }

    public Atom(Atom atom)
    {
        this.size = atom.size;
        this.type = atom.type;
        this.headerSize = atom.headerSize;
    }

    /**
     * The header of a box read by {@link com.drew.imaging.isobmff.IsoBoxIterator}, for the atom
     * classes of this package. Pass it with the iterator's payload reader, whose positions start
     * at 0 like those of a reader over the payload bytes.
     */
    public Atom(@NotNull IsoBox box)
    {
        this.size = box.size;
        this.type = box.type;
        this.headerSize = (int) (box.payloadOffset - box.offset);
    }
}
