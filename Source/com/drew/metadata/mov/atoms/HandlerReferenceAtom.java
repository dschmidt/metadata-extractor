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

import com.drew.lang.SequentialReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * https://developer.apple.com/library/content/documentation/QuickTime/QTFF/QTFFChap2/qtff2.html#//apple_ref/doc/uid/TP40000939-CH204-BBCIBHFD
 *
 * @author Payton Garland
 * @deprecated the readers of {@link com.drew.imaging.mp4.Mp4MetadataReader} read this atom into the directories of {@code com.drew.metadata.mp4}.
 */
@Deprecated
public class HandlerReferenceAtom extends FullAtom
{
    public String getComponentType()
    {
        return componentSubtype;
    }

    String componentType;
    String componentSubtype;
    String componentName;

    public HandlerReferenceAtom(SequentialReader reader, Atom atom) throws IOException
    {
        super(reader, atom);

        componentType = reader.getString(4);
        componentSubtype = reader.getString(4);
        reader.skip(4); // Reserved
        reader.skip(4); // Reserved
        reader.skip(4); // Reserved
        // QuickTime writes the name as a counted string; the ISO base media form, which Sony
        // and others use with a QuickTime brand, writes it NUL-terminated without a count
        int remaining = (int) (atom.size - atom.headerSize - 24 - 1);
        int count = reader.getUInt8();
        if (atom.size == -1 || count <= remaining)
            componentName = reader.getString(count);
        else
            componentName = (char) count + reader.getNullTerminatedString(remaining, StandardCharsets.UTF_8, false);
    }
}
