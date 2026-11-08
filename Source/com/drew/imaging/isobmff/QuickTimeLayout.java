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
package com.drew.imaging.isobmff;

import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;

import java.io.IOException;

/**
 * The standard layout with the deviations of the QuickTime File Format, which MP4 files written
 * by Apple's and many other tools share: {@code meta} in {@code moov} and {@code trak} has no
 * FullBox header, {@code keys} has one plus an entry count, and sound descriptions of version 1
 * and 2 have 16 and 36 more bytes before their boxes than an audio sample entry, in a
 * {@code stsd} of version 0.
 */
public class QuickTimeLayout extends IsoStandardLayout
{
    public static final QuickTimeLayout INSTANCE = new QuickTimeLayout();

    private static final class SampleDescriptionVersion
    {
        final int value;

        SampleDescriptionVersion(int value)
        {
            this.value = value;
        }
    }

    @Override
    public boolean enterContainer(@NotNull IsoBoxContext box) throws IOException
    {
        String type = box.box().type;
        IsoBoxContext parent = box.parent();
        String parentType = parent == null || parent.parent() == null ? "" : parent.box().type;
        SequentialReader payload = box.payload();
        if (type.equals("meta") && (parentType.equals("moov") || parentType.equals("trak")))
            return true;
        if (type.equals("keys") && parentType.equals("meta")) {
            payload.skip(8);
            return true;
        }
        if (type.equals("stsd")) {
            box.put(SampleDescriptionVersion.class, new SampleDescriptionVersion(payload.getUInt8()));
            payload.skip(7);
            return true;
        }
        if (parentType.equals("stsd") && "soun".equals(IsoStandardLayout.handlerType(box))) {
            SampleDescriptionVersion stsd = parent.get(SampleDescriptionVersion.class);
            if (stsd != null && stsd.value == 0) {
                payload.skip(8);
                int version = payload.getUInt16();
                payload.skip((version == 1 ? 44 : version == 2 ? 64 : 28) - 10);
                return true;
            }
        }
        return super.enterContainer(box);
    }
}
