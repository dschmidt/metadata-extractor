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

import static org.junit.Assert.assertArrayEquals;
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
        public void readSample(@NotNull IsoBoxContext file, @NotNull IsoSamples.Track track, @NotNull IsoSamples.Sample sample, @NotNull SequentialReader data) throws IOException
        {
            samples.add(track.getIndex() + ":" + sample.getIndex() + "=" + data.getString((int) sample.getSize()));
        }
    }

    /** Reads the text tracks that are not chapter tracks, with each sample's time. */
    private static final class TextRecorder implements IsoSamples.SampleReader
    {
        final List<String> samples = new ArrayList<String>();
        final List<String> tracks = new ArrayList<String>();

        @Override
        public boolean accepts(@NotNull IsoSamples.Track track)
        {
            return "text".equals(track.getHandlerType()) && "tx3g".equals(track.getFormat()) && !track.isReferencedAs("chap");
        }

        @Override
        public void readSample(@NotNull IsoBoxContext file, @NotNull IsoSamples.Track track, @NotNull IsoSamples.Sample sample, @NotNull SequentialReader data) throws IOException
        {
            String track_ = track.getId() + "/" + track.getTimescale() + "/" + Integer.toHexString(track.getLanguageCode());
            if (!tracks.contains(track_))
                tracks.add(track_);
            samples.add(track.getId() + ":" + sample.getIndex() + "@" + sample.getTime() + "+" + sample.getDuration() + "=" + data.getString((int) sample.getSize()));
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
    public void testPresentationTimeFollowsTheEditList() throws Exception
    {
        IsoSamples.Track track = new IsoSamples.Track();
        track.timescale = 600;
        // no edit list: presented as decoded
        assertEquals(42, IsoSamples.presentationTime(track, 600, 42));
        // an empty edit of 880 movie ticks, then the media from its start: a Live Photo's still image track
        track.edits = new long[] {880, -1, 1, 0};
        assertEquals(880, IsoSamples.presentationTime(track, 600, 0));
        assertEquals(-1, IsoSamples.presentationTime(track, 600, 1));
        // the movie's time scale differs from the track's: 880 ticks of 1000 a second are 528 of 600
        assertEquals(528, IsoSamples.presentationTime(track, 1000, 0));
        // an edit starting inside the media advances the samples after it; those before it are not presented
        track.edits = new long[] {0, 300};
        assertEquals(-1, IsoSamples.presentationTime(track, 600, 100));
        assertEquals(50, IsoSamples.presentationTime(track, 600, 350));
        // a segment duration of 0 runs to the end
        track.edits = new long[] {100, 0, 0, 100};
        assertEquals(99, IsoSamples.presentationTime(track, 600, 99));
        assertEquals(100 + 5000, IsoSamples.presentationTime(track, 600, 5100));
    }

    @Test
    public void testSamplesCarryTheirPresentationTimeAndTheTrackItsSampleEntry() throws Exception
    {
        final List<String> got = new ArrayList<String>();
        final byte[][] entry = new byte[1][];
        IsoSamples.SampleReader recorder = new IsoSamples.SampleReader()
        {
            @Override
            public boolean accepts(@NotNull IsoSamples.Track track)
            {
                entry[0] = track.getSampleEntry();
                return "mebx".equals(track.getFormat());
            }

            @Override
            public void readSample(@NotNull IsoBoxContext file, @NotNull IsoSamples.Track track, @NotNull IsoSamples.Sample sample, @NotNull SequentialReader data) throws IOException
            {
                got.add(sample.getTime() + "->" + sample.getPresentationTime() + "=" + data.getString((int) sample.getSize()));
            }
        };
        // mvhd at 1000 ticks a second, the track at 600; an empty edit of 500 movie ticks before the media: 300 track ticks
        byte[] mvhd = box("mvhd", new byte[4], int32(0), int32(0), int32(1000), int32(0));
        byte[] mdhd = box("mdhd", new byte[4], int32(0), int32(0), int32(600), int32(0), int32(0));
        byte[] elst = box("elst", new byte[4], int32(2), int32(500), int32(-1), int32(0x10000), int32(0), int32(0), int32(0x10000));
        byte[] stsd = box("stsd", new byte[4], int32(1), box("mebx", new byte[8], box("keys", new byte[] {1, 2, 3})));
        byte[] stts = box("stts", new byte[4], int32(1), int32(2), int32(10));
        // composition offsets of version 1: the first sample is presented 5 ticks after decoding, the second 2 before
        byte[] ctts = box("ctts", new byte[] {1, 0, 0, 0}, int32(2), int32(1), int32(5), int32(1), int32(-2));
        byte[] stsc = box("stsc", new byte[4], int32(1), int32(1), int32(2), int32(1));
        byte[] stsz = box("stsz", new byte[4], int32(0), int32(2), int32(2), int32(3));
        byte[] hdlr = box("hdlr", new byte[8], ascii("meta"), new byte[13]);
        byte[] probe = box("moov", mvhd, box("trak", box("edts", elst), box("mdia", mdhd, hdlr, box("minf", box("stbl", stsd, stts, ctts, stsc, stsz, box("stco", new byte[4], int32(1), int32(0)))))));
        int data = probe.length + 8;
        byte[] moov = box("moov", mvhd, box("trak", box("edts", elst), box("mdia", mdhd, hdlr, box("minf", box("stbl", stsd, stts, ctts, stsc, stsz, box("stco", new byte[4], int32(1), int32(data)))))));
        List<String> errors = new ArrayList<String>();
        read(join(moov, box("mdat", ascii("aabbb"))), recorder, errors);

        assertEquals(Arrays.asList("0->305=aa", "10->308=bbb"), got);
        assertArrayEquals(join(new byte[8], box("keys", new byte[] {1, 2, 3})), entry[0]);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void testSamplesBeforeMoovAreKeptUntilMoovIsRead() throws Exception
    {
        Recorder recorder = new Recorder();
        List<String> errors = new ArrayList<String>();
        read(file(true), recorder, errors);
        assertEquals(Arrays.asList("1:0=aa", "1:1=bbb", "1:2=c"), recorder.samples);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void testSamplesBeforeMoovAreReportedWhenNotKept() throws Exception
    {
        Recorder recorder = new Recorder();
        List<String> errors = new ArrayList<String>();
        // a buffer limit of 0 keeps nothing
        IsoBoxReaders readers = new IsoBoxReaders(PLAIN, Collections.singletonList(IsoSamples.readers(recorder, 100)), 0);
        readers.read(new ByteArrayInputStream(file(true)), IsoBoxContext.root(new Metadata(), collect(errors)));
        assertTrue(recorder.samples.isEmpty());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("not read"));
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
    public void testCappedTracksAreReported() throws Exception
    {
        List<String> errors = new ArrayList<String>();
        new IsoBoxReaders(PLAIN, Collections.singletonList(IsoSamples.readers(new Recorder(), 2)))
            .read(new IsoBoxIterator(new ByteArrayInputStream(file(false))), IsoBoxContext.root(new Metadata(), collect(errors)));
        assertEquals(Collections.singletonList("Track 0 has more than 2 samples; the rest are not read"), errors);
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

    private static final int ENG = (5 << 10) | (14 << 5) | 7;

    @Test
    public void testTimesComeFromTheTimeToSampleTable() throws Exception
    {
        byte[] data = ascii("aabbbc");
        byte[] stts = box("stts", new byte[4], int32(2), int32(2), int32(500), int32(1), int32(2000));
        byte[] stsc = box("stsc", new byte[4], int32(1), int32(1), int32(3), int32(1));
        byte[] stsz = box("stsz", new byte[4], int32(0), int32(3), int32(2), int32(3), int32(1));
        byte[] probe = box("moov", textTrak(3, null, stts, stsc, stsz, stco(0)));
        byte[] file = join(box("moov", textTrak(3, null, stts, stsc, stsz, stco(probe.length + 8))), box("mdat", data));
        TextRecorder recorder = new TextRecorder();
        List<String> errors = new ArrayList<String>();
        read(file, recorder, errors);
        assertEquals(Arrays.asList("3:0@0+500=aa", "3:1@500+500=bbb", "3:2@1000+2000=c"), recorder.samples);
        assertEquals(Collections.singletonList("3/1000/" + Integer.toHexString(ENG)), recorder.tracks);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void testReferencesFromLaterTracksAreKnownBeforeSamplesAreRead() throws Exception
    {
        byte[] stts = box("stts", new byte[4], int32(1), int32(1), int32(1000));
        byte[] stsc = box("stsc", new byte[4], int32(1), int32(1), int32(1), int32(1));
        byte[] stsz = box("stsz", new byte[4], int32(2), int32(1));
        // track 2 holds chapter titles, as track 3 says after it; track 4 is a subtitle track
        byte[] video = box("trak", box("tkhd", new byte[12], int32(3), new byte[4]), box("tref", box("chap", int32(2))),
            box("mdia", box("mdhd", new byte[12], int32(1000), int32(0), new byte[4]), box("hdlr", new byte[8], ascii("vide"), new byte[13])));
        byte[] probe = box("moov", textTrak(2, null, stts, stsc, stsz, stco(0)), video, textTrak(4, null, stts, stsc, stsz, stco(0)));
        int data = probe.length + 8;
        byte[] file = join(box("moov", textTrak(2, null, stts, stsc, stsz, stco(data)), video, textTrak(4, null, stts, stsc, stsz, stco(data + 2))),
            box("mdat", ascii("chsu")));
        TextRecorder recorder = new TextRecorder();
        read(file, recorder, new ArrayList<String>());
        assertEquals(Collections.singletonList("4:0@0+1000=su"), recorder.samples);
    }

    @Test
    public void testTablesThatClaimMoreThanTheyHold() throws Exception
    {
        byte[] stts = box("stts", new byte[4], int32(999), int32(3), int32(10));
        byte[] stsc = box("stsc", new byte[4], int32(999), int32(1), int32(3), int32(1));
        byte[] stsz = box("stsz", new byte[4], int32(0), int32(1000000000), int32(2), int32(3), int32(1));
        byte[] probe = box("moov", textTrak(3, null, stts, stsc, stsz, box("stco", new byte[4], int32(999), int32(0))));
        byte[] file = join(box("moov", textTrak(3, null, stts, stsc, stsz, box("stco", new byte[4], int32(999), int32(probe.length + 8)))),
            box("mdat", ascii("aabbbc")));
        TextRecorder recorder = new TextRecorder();
        List<String> errors = new ArrayList<String>();
        read(file, recorder, errors);
        assertEquals(Arrays.asList("3:0@0+10=aa", "3:1@10+10=bbb", "3:2@20+10=c"), recorder.samples);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void testRunDataOffsetIsRelativeToTheFragmentBase() throws Exception
    {
        byte[] moov = box("moov", textTrak(2, null), box("mvex", box("trex", new byte[4], int32(2), int32(1), int32(1000), int32(2), int32(0))));
        // tfhd: track 2 with an explicit base 4 bytes before the sample; trun: data offset 4, one sample of the default size
        byte[] probe = moof(box("traf", box("tfhd", new byte[] {0, 0, 0, 1}, int32(2), int64(0)), box("trun", new byte[] {0, 0, 0, 1}, int32(1), int32(4))));
        long sample = moov.length + probe.length + 8;
        byte[] file = join(moov, moof(box("traf", box("tfhd", new byte[] {0, 0, 0, 1}, int32(2), int64(sample - 4)),
            box("trun", new byte[] {0, 0, 0, 1}, int32(1), int32(4)))), box("mdat", ascii("hi")));
        TextRecorder recorder = new TextRecorder();
        List<String> errors = new ArrayList<String>();
        read(file, recorder, errors);
        assertEquals(Collections.singletonList("2:0@0+1000=hi"), recorder.samples);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test(timeout = 10000)
    public void testHugeRunsOfOtherTracksAreNotWalked() throws Exception
    {
        byte[] moov = box("moov", box("trak", box("tkhd", new byte[12], int32(1), new byte[4])), textTrak(2, null),
            box("mvex", box("trex", new byte[4], int32(1), int32(1), int32(1), int32(2), int32(0)),
                box("trex", new byte[4], int32(2), int32(1), int32(1000), int32(2), int32(0))));
        // 200 runs of 4 billion default-sized samples of track 1, then one sample of track 2 at the moof
        byte[] huge = box("traf", box("tfhd", new byte[] {0, 2, 0, 0}, int32(1)), box("trun", new byte[4], int32(0xFFFFFFFF)));
        byte[][] runs = new byte[201][];
        Arrays.fill(runs, 0, 200, huge);
        runs[200] = box("traf", box("tfhd", new byte[] {0, 2, 0, 0}, int32(2)), box("trun", new byte[] {0, 0, 0, 1}, int32(1), int32(0)));
        byte[] probe = moof(join(runs));
        runs[200] = box("traf", box("tfhd", new byte[] {0, 2, 0, 0}, int32(2)), box("trun", new byte[] {0, 0, 0, 1}, int32(1), int32(probe.length + 8)));
        byte[] file = join(moov, moof(join(runs)), box("mdat", ascii("ok")));
        TextRecorder recorder = new TextRecorder();
        read(file, recorder, new ArrayList<String>());
        assertEquals(Collections.singletonList("2:0@0+1000=ok"), recorder.samples);
    }

    @Test
    public void testFragmentAfterADamagedOneIsSkipped() throws Exception
    {
        byte[] moov = box("moov", box("trak", box("tkhd", new byte[12], int32(1), new byte[4])), textTrak(2, null),
            box("mvex", box("trex", new byte[4], int32(2), int32(1), int32(1000), int32(2), int32(0))));
        // track 1's tfhd promises a base offset it does not hold; track 2's traf has no base of its own
        byte[] damaged = moof(join(box("traf", box("tfhd", new byte[] {0, 0, 0, 1}, int32(1))),
            box("traf", box("tfhd", new byte[4], int32(2)), box("trun", new byte[4], int32(1)))));
        byte[] probe = moof(box("traf", box("tfhd", new byte[] {0, 2, 0, 0}, int32(2)), box("trun", new byte[] {0, 0, 0, 1}, int32(1), int32(0))));
        byte[] later = moof(box("traf", box("tfhd", new byte[] {0, 2, 0, 0}, int32(2)), box("trun", new byte[] {0, 0, 0, 1}, int32(1), int32(probe.length + 8))));
        byte[] file = join(moov, damaged, box("mdat", ascii("xx")), later, box("mdat", ascii("ok")));
        TextRecorder recorder = new TextRecorder();
        List<String> errors = new ArrayList<String>();
        read(file, recorder, errors);
        assertEquals(Collections.singletonList("2:0@0+1000=ok"), recorder.samples);
        assertTrue(errors.toString(), errors.toString().contains("follows a damaged track fragment"));
    }

    @Test
    public void testFragmentWithoutHeaderIsReportedOnce() throws Exception
    {
        byte[] moov = box("moov", textTrak(2, null), box("mvex", box("trex", new byte[4], int32(2), int32(1), int32(1000), int32(2), int32(0))));
        byte[] trun = box("trun", new byte[4], int32(1));
        byte[] file = join(moov, moof(box("traf", trun, trun)), box("mdat", ascii("xx")));
        List<String> errors = new ArrayList<String>();
        read(file, new TextRecorder(), errors);
        assertEquals(Collections.singletonList("Track fragment without a 'tfhd' before its runs; its data cannot be placed"), errors);
    }

    private static byte[] moof(byte[] trafs)
    {
        return box("moof", box("mfhd", new byte[4], int32(1)), trafs);
    }

    /** A text track with {@code tx3g} samples, an English 1000 Hz {@code mdhd} and {@code tables}. */
    private static byte[] textTrak(int id, byte[] tref, byte[]... tables)
    {
        byte[] mdhd = box("mdhd", new byte[12], int32(1000), int32(0), new byte[] {(byte) (ENG >> 8), (byte) ENG}, new byte[2]);
        byte[] hdlr = box("hdlr", new byte[8], ascii("text"), new byte[13]);
        byte[] stsd = box("stsd", new byte[4], int32(1), box("tx3g", new byte[8]));
        return box("trak", box("tkhd", new byte[12], int32(id), new byte[4]), tref == null ? new byte[0] : tref,
            box("mdia", mdhd, hdlr, box("minf", box("stbl", stsd, join(tables)))));
    }

    private static byte[] stco(int offset)
    {
        return box("stco", new byte[4], int32(1), int32(offset));
    }

    private static byte[] int64(long value)
    {
        return join(int32((int) (value >> 32)), int32((int) value));
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
