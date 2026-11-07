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
package com.drew.metadata.heif.boxes;

import com.drew.lang.SequentialReader;

import java.io.IOException;

/**
 * ISO/IEC 14496-12:2015 pg.80
 *
 * @deprecated the HEIF reader no longer uses this class: {@link com.drew.imaging.isobmff.IsoItems}
 * reads {@code pitm}, which it reads but does not use for it. To be removed in a future major version.
 */
@Deprecated
public class PrimaryItemBox extends FullBox
{
    long itemID;

    public PrimaryItemBox(SequentialReader reader, Box box) throws IOException
    {
        super(reader, box);

        if (version == 0) {
            itemID = reader.getUInt16();
        } else {
            itemID = reader.getUInt32();
        }
    }
}
