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
 *    https://drewnoakes.com/code/exif/
 *    https://github.com/drewnoakes/metadata-extractor
 */
package com.drew.imaging.isobmff;

import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.Metadata;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IsoSamplesTest
{
    private static final IsoBoxLayout PLAIN = new IsoBoxLayout()
    {
        @Override
        public boolean enterContainer(@NotNull IsoBoxContext box)
        {
            return true;
        }
    };

    private static final class Recorder implements IsoSamples.SampleReader
    {
        final List<String> samples = new ArrayList<String>();

        @Override
        public boolean accepts(@NotNull IsoSamples.Track track)
        {
            return "meta".equals(track.getHandlerType()) && "mebx".equals(track.getFormat());
        }

        @Override
        public void readSample(@NotNull IsoBoxContext file, @NotNull IsoSamples.Track track, int index, @NotNull SequentialReader data) throws IOException
        {
            samples.add(track.getIndex() + ":" + index + "=" + data.getString((int) data.available()));
        }
    }

    @Test
    public void testSamplesOfAcceptedTracksAreRead() throws Exception
    {
        Recorder recorder = new Recorder();
        List<String> errors = new ArrayList<String>();
        read(file(false), recorder, errors);
        assertEquals(Arrays.asList("1:0=aa", "1:1=bbb", "1:2=c"), recorder.samples);
        assertTrue(errors.isEmpty());
    }

    @Test
    public void testSamplesBeforeMoovAreReadInASecondPass() throws Exception
    {
        Recorder recorder = new Recorder();
        List<String> errors = new ArrayList<String>();
        byte[] file = file(true);
        IsoBoxReaders readers = new IsoBoxReaders(PLAIN, Collections.singletonList(IsoSamples.readers(recorder, 100)));
        IsoBoxContext root = IsoBoxContext.root(new Metadata(), collect(errors));
        readers.read(new IsoBoxIterator(new ByteArrayInputStream(file)), root);
        assertTrue(recorder.samples.isEmpty());
        readers.readRanges(new IsoBoxIterator(new ByteArrayInputStream(file)), root);
        assertEquals(Arrays.asList("1:0=aa", "1:1=bbb", "1:2=c"), recorder.samples);
        assertTrue(errors.isEmpty());
    }

    @Test
    public void testSampleCountIsCapped() throws Exception
    {
        Recorder recorder = new Recorder();
        IsoBoxReaders readers = new IsoBoxReaders(PLAIN, Collections.singletonList(IsoSamples.readers(recorder, 2)));
        readers.read(new IsoBoxIterator(new ByteArrayInputStream(file(false))), IsoBoxContext.root(new Metadata(), collect(new ArrayList<String>())));
        assertEquals(Arrays.asList("1:0=aa", "1:1=bbb"), recorder.samples);
    }

    @Test
    public void testSamplesOfTrackFragmentsAreRead() throws Exception
    {
        Recorder recorder = new Recorder();
        List<String> errors = new ArrayList<String>();
        byte[] tkhd = box("tkhd", new byte[12], int32(7), new byte[4]);
        byte[] hdlr = box("hdlr", new byte[8], ascii("meta"), new byte[13]);
        byte[] stsd = box("stsd", new byte[4], int32(1), box("mebx", new byte[8]));
        byte[] trak = box("trak", tkhd, box("mdia", hdlr, box("minf", box("stbl", stsd))));
        // trex: track 7, default sample size 2
        byte[] mvex = box("mvex", box("trex", new byte[4], int32(7), int32(1), int32(0), int32(2), int32(0)));
        byte[] moov = box("moov", box("trak", box("tkhd", new byte[12], int32(1), new byte[4])), trak, mvex);
        // tfhd: track 7, default-base-is-moof; trun: data offset, two samples of the default size
        byte[] traf = box("traf", box("tfhd", new byte[] {0, 2, 0, 0}, int32(7)),
            box("trun", new byte[] {0, 0, 0, 1}, int32(2), int32(0)));
        int dataOffset = 8 + traf.length + 8;
        traf = box("traf", box("tfhd", new byte[] {0, 2, 0, 0}, int32(7)),
            box("trun", new byte[] {0, 0, 0, 1}, int32(2), int32(dataOffset)));
        // a second run in the same fragment, without data offset, continues after the first one, with explicit sizes
        byte[] traf2 = box("traf", box("tfhd", new byte[] {0, 0, 0, 0}, int32(7)),
            box("trun", new byte[] {0, 0, 2, 0}, int32(1), int32(3)));
        byte[] moof = box("moof", traf, traf2);
        dataOffset = 8 + traf.length + traf2.length + 8;
        traf = box("traf", box("tfhd", new byte[] {0, 2, 0, 0}, int32(7)),
            box("trun", new byte[] {0, 0, 0, 1}, int32(2), int32(dataOffset)));
        moof = box("moof", traf, traf2);
        byte[] file = join(moov, moof, box("mdat", ascii("aabbccc")));
        new IsoBoxReaders(PLAIN, Collections.singletonList(IsoSamples.readers(recorder, 100)))
            .read(new IsoBoxIterator(new ByteArrayInputStream(file)), IsoBoxContext.root(new Metadata(), collect(errors)));
        assertEquals(Arrays.asList("1:0=aa", "1:1=bb", "1:2=ccc"), recorder.samples);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void testCompactSampleSizesAreRead() throws Exception
    {
        Recorder recorder = new Recorder();
        byte[] stz2 = box("stz2", new byte[4], new byte[] {0, 0, 0, 4}, int32(3), new byte[] {0x23, 0x10});
        byte[] probe = box("moov", trak("meta", "mebx", stsc(), stz2, stco(0, 0)));
        int start = probe.length + 8;
        byte[] moov = box("moov", trak("meta", "mebx", stsc(), stz2, stco(start, start + 9)));
        read(join(moov, box("mdat", ascii("aabbbXXXXc"))), recorder, new ArrayList<String>());
        assertEquals(Arrays.asList("0:0=aa", "0:1=bbb", "0:2=c"), recorder.samples);
    }

    private static void read(byte[] file, IsoSamples.SampleReader reader, List<String> errors)
    {
        new IsoBoxReaders(PLAIN, Collections.singletonList(IsoSamples.readers(reader, 100)))
            .read(new IsoBoxIterator(new ByteArrayInputStream(file)), IsoBoxContext.root(new Metadata(), collect(errors)));
    }

    /**
     * A video track without samples, then a {@code mebx} track with samples "aa" and "bbb" in a
     * first chunk and "c" in a second, with 4 bytes of other data between the chunks.
     */
    private static byte[] file(boolean mdatFirst)
    {
        byte[] data = ascii("aabbbXXXXc");
        int mdatPayload = mdatFirst ? 8 : 0;
        byte[] video = trak("vide", "avc1", new byte[0], new byte[0], new byte[0]);
        byte[] probe = box("moov", video, trak("meta", "mebx", stsc(), stsz(), stco(0, 0)));
        if (!mdatFirst)
            mdatPayload = probe.length + 8;
        byte[] moov = box("moov", video, trak("meta", "mebx", stsc(), stsz(), stco(mdatPayload, mdatPayload + 9)));
        byte[] mdat = box("mdat", data);
        return mdatFirst ? join(mdat, moov) : join(moov, mdat);
    }

    private static byte[] trak(String handler, String format, byte[] stsc, byte[] stsz, byte[] stco)
    {
        byte[] hdlr = box("hdlr", new byte[8], ascii(handler), new byte[13]);
        byte[] stsd = box("stsd", new byte[4], int32(1), box(format, new byte[8]));
        return box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd, stsc, stsz, stco))));
    }

    private static byte[] stsc()
    {
        return box("stsc", new byte[4], int32(2), int32(1), int32(2), int32(1), int32(2), int32(1), int32(1));
    }

    private static byte[] stsz()
    {
        return box("stsz", new byte[4], int32(0), int32(3), int32(2), int32(3), int32(1));
    }

    private static byte[] stco(int first, int second)
    {
        return box("stco", new byte[4], int32(2), int32(first), int32(second));
    }

    private static IsoBoxContext.ErrorHandler collect(final List<String> errors)
    {
        return new IsoBoxContext.ErrorHandler()
        {
            @Override
            public void error(@Nullable IsoBox box, @NotNull String message, @Nullable IOException cause)
            {
                errors.add(message);
            }
        };
    }

    private static byte[] box(String type, byte[]... payload)
    {
        byte[] content = join(payload);
        return join(int32(8 + content.length), ascii(type), content);
    }

    private static byte[] int32(int value)
    {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
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
