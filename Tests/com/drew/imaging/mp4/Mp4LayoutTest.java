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
package com.drew.imaging.mp4;

import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.lang.annotations.NotNull;
import com.drew.metadata.Metadata;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class Mp4LayoutTest
{
    @Test
    public void testSoundDescriptionsOfQuickTimeAndIso() throws Exception
    {
        final List<Integer> esds = new ArrayList<Integer>();
        IsoBoxReader reader = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                esds.add((int) box.payload().getUInt8());
            }
        }, "moov/trak/mdia/minf/stbl/stsd/mp4a/esds");
        // QuickTime: a stsd of version 0 with a sound description of version 1 (16 more bytes)
        // and one of version 2 (36 more); ISO: a stsd of version 1 with an audio sample entry of
        // version 1, which has the same 28 bytes as version 0
        byte[] file = box("moov",
            trak(new byte[] {0, 0, 0, 0}, entry(1, 44 - 10, 1)),
            trak(new byte[] {0, 0, 0, 0}, entry(2, 64 - 10, 2)),
            trak(new byte[] {1, 0, 0, 0}, entry(1, 28 - 10, 3)),
            trak(new byte[] {0, 0, 0, 0}, entry(0, 28 - 10, 4)));
        List<IsoBoxReader> readers = new ArrayList<IsoBoxReader>(Mp4MetadataReader.ALL_READERS);
        readers.add(reader);
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(file), readers);
        assertEquals(Arrays.asList(1, 2, 3, 4), esds);
        assertFalse(metadata.hasErrors());
    }

    private static byte[] entry(int version, int fieldsAfterVersion, int marker)
    {
        return box("mp4a", new byte[8], new byte[] {0, (byte) version}, new byte[fieldsAfterVersion], box("esds", new byte[] {(byte) marker}));
    }

    private static byte[] trak(byte[] stsdHeader, byte[] entry)
    {
        byte[] hdlr = box("hdlr", new byte[8], ascii("soun"), new byte[13]);
        byte[] stsd = box("stsd", stsdHeader, new byte[] {0, 0, 0, 1}, entry);
        return box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd))));
    }

    private static byte[] box(String type, byte[]... payload)
    {
        byte[] content = join(payload);
        int size = 8 + content.length;
        return join(new byte[] {(byte) (size >> 24), (byte) (size >> 16), (byte) (size >> 8), (byte) size}, ascii(type), content);
    }

    private static byte[] ascii(String value)
    {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] join(byte[]... parts)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts)
            out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
