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

import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoInput;
import com.drew.lang.Rational;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.mp4.LivePhotoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4MediaDirectory;
import com.drew.metadata.mp4.media.Mp4MetaDirectory;
import com.drew.metadata.mp4.media.Mp4SoundDirectory;
import com.drew.metadata.mp4.media.Mp4SubtitleDirectory;
import com.drew.metadata.mp4.media.Mp4TextDirectory;
import com.drew.metadata.mp4.media.Mp4TimecodeDirectory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The readers MP4 and QuickTime files share, on synthetic boxes. */
public class Mp4ReadersTest
{
    /** Seconds from 1904 to the Unix epoch, the base of the formats' times. */
    private static final long EPOCH_1904 = 2082844800L;

    @Test
    public void testKeysAndValuesFillTheMetadataDirectory() throws Exception
    {
        // 1: a string; 21: a signed integer of 2 bytes; 77: an unsigned 32-bit; 24: a double
        byte[] meta = box("meta",
            handler("mdta"),
            box("keys", int32(0), int32(4), key("com.apple.quicktime.make"), key("com.apple.quicktime.rating.user"),
                key("com.apple.quicktime.direction.facing"), key("com.apple.quicktime.direction.motion")),
            box("ilst",
                box(int32(1), box("data", int32(1), int32(0), ascii("Apple"))),
                box(int32(2), box("data", int32(21), int32(0), int16(-2))),
                box(int32(3), box("data", int32(77), int32(0), int32(0xFFFFFFF0L))),
                box(int32(4), box("data", int32(24), int32(0), ByteBuffer.allocate(8).putDouble(26.5).array())),
                box(int32(5), box("data", int32(1), int32(0), ascii("no key for this index")))));
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp("qt  "), box("moov", meta))));

        QuickTimeMetadataDirectory directory = metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class);
        assertNotNull(directory);
        assertEquals("Apple", directory.getString(QuickTimeMetadataDirectory.TAG_MAKE));
        assertEquals(-2, directory.getInt(QuickTimeMetadataDirectory.getTagType("com.apple.quicktime.rating.user")));
        assertEquals(0xFFFFFFF0L, directory.getLong(QuickTimeMetadataDirectory.getTagType("com.apple.quicktime.direction.facing")));
        assertEquals(26.5, directory.getDouble(QuickTimeMetadataDirectory.getTagType("com.apple.quicktime.direction.motion")), 0d);
        assertEquals(4, directory.getTagCount());
        assertNoErrors(metadata);
    }

    @Test
    public void testITunesListFillsTheMetadataDirectory() throws Exception
    {
        // 'udta/meta' is a FullBox, unlike QuickTime's 'moov/meta'
        byte[] meta = box("meta", int32(0),
            handler("mdir"),
            box("ilst", box("aART", box("data", int32(1), int32(0), ascii("The Artist")))));
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp("isom"), box("moov", box("udta", meta)))));

        QuickTimeMetadataDirectory directory = metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class);
        assertNotNull(directory);
        Integer tag = QuickTimeMetadataDirectory.getTagType("aART");
        assertNotNull(tag);
        assertEquals("The Artist", directory.getString(tag));
        assertNoErrors(metadata);
    }

    @Test
    public void testAMetaWithAnotherHandlerAddsNoDirectory() throws Exception
    {
        byte[] meta = box("meta", handler("xxxx"), box("ilst", box(int32(1), box("data", int32(1), int32(0), ascii("x")))));
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp("qt  "), box("moov", meta))));

        assertNull(metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class));
        assertNoErrors(metadata);
    }

    @Test
    public void testTheHandlerChoosesTheMediaDirectoryAndTheMediaHeaderDatesIt() throws Exception
    {
        long creation = EPOCH_1904 + 86400;
        // 300 frames in 6000 ticks at a time scale of 600: 30 frames per second
        byte[] video = box("trak", box("mdia",
            mediaHeader(creation, creation + 60, 600, 6000),
            handler("vide"),
            box("minf", box("stbl",
                box("stts", int32(0), int32(2), int32(100), int32(20), int32(200), int32(20))))));
        byte[] sound = box("trak", box("mdia",
            mediaHeader(creation, creation + 60, 44100, 441000),
            handler("soun"),
            box("minf", box("stbl", box("stts", int32(0), int32(1), int32(441000), int32(1))))));
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp("qt  "), box("moov", video, sound))));

        Mp4VideoDirectory videoDirectory = metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class);
        assertNotNull(videoDirectory);
        assertEquals(86400 * 1000L, videoDirectory.getDate(Mp4MediaDirectory.TAG_CREATION_TIME).getTime());
        assertEquals(30f, videoDirectory.getFloat(Mp4VideoDirectory.TAG_FRAME_RATE), 0f);

        Mp4SoundDirectory soundDirectory = metadata.getFirstDirectoryOfType(Mp4SoundDirectory.class);
        assertNotNull(soundDirectory);
        assertEquals(44100d, soundDirectory.getDouble(Mp4SoundDirectory.TAG_AUDIO_SAMPLE_RATE), 0d);
        assertNoErrors(metadata);
    }

    @Test
    public void testEachTrackIsDatedFromItsOwnMediaHeader() throws Exception
    {
        long first = EPOCH_1904 + 1000;
        long second = EPOCH_1904 + 2000;
        // the first track has 'mdhd' before 'hdlr', the second after it, the third has none
        byte[] video = box("trak", box("mdia", mediaHeader(first, first, 600, 600), handler("vide")));
        byte[] sound = box("trak", box("mdia", handler("soun"), mediaHeader(second, second, 48000, 48000),
            box("minf", box("stbl", box("stts", int32(0), int32(1), int32(48000), int32(1))))));
        byte[] text = box("trak", box("mdia", handler("text")));
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp("qt  "), box("moov", video, sound, text))));

        assertEquals(1000 * 1000L, metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class).getDate(Mp4MediaDirectory.TAG_CREATION_TIME).getTime());
        Mp4SoundDirectory soundDirectory = metadata.getFirstDirectoryOfType(Mp4SoundDirectory.class);
        assertEquals(2000 * 1000L, soundDirectory.getDate(Mp4MediaDirectory.TAG_CREATION_TIME).getTime());
        assertEquals(48000d, soundDirectory.getDouble(Mp4SoundDirectory.TAG_AUDIO_SAMPLE_RATE), 0d);
        assertNull(metadata.getFirstDirectoryOfType(Mp4TextDirectory.class).getDate(Mp4MediaDirectory.TAG_CREATION_TIME));
        assertNoErrors(metadata);
    }

    @Test
    public void testTheHandlerChoosesTheDirectoryOfTimecodeTextAndSubtitleTracks() throws Exception
    {
        byte[] tcmi = box("tcmi", int32(0), int16(1), int16(0x0001), int16(12), int16(0), int16(1), int16(2), int16(3), int16(4), int16(5), int16(6), new byte[] {5}, ascii("Arial"));
        byte[] timecode = box("trak", box("mdia", mediaHeader(EPOCH_1904, EPOCH_1904, 600, 600), handler("tmcd"),
            box("minf", box("gmhd", box("tmcd", tcmi)), box("stbl",
                box("stsd", int32(0), int32(1), box("tmcd", new byte[8], int32(0), int32(0x0003), int32(600), int32(20), new byte[] {30, 0}))))));
        byte[] text = box("trak", box("mdia", handler("text"), box("minf", box("stbl",
            box("stsd", int32(0), int32(1), box("text", new byte[8], int32(0x0002), int32(1), int16(1), int16(2), int16(3),
                new byte[8], new byte[8], int16(21), int16(0x0002), new byte[3], int16(4), int16(5), int16(6), new byte[] {4}, ascii("Font")))))));
        byte[] subtitle = box("trak", box("mdia", handler("sbtl"), box("minf", box("stbl",
            box("stsd", int32(0), int32(1), box("tx3g", new byte[8], int32(0x40000000), new byte[6], new byte[8], new byte[4],
                int16(7), new byte[] {1, 12}, int16(1), int16(2), int16(3)))))));
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp("qt  "), box("moov", timecode, text, subtitle))));

        Mp4TimecodeDirectory timecodeDirectory = metadata.getFirstDirectoryOfType(Mp4TimecodeDirectory.class);
        assertNotNull(timecodeDirectory);
        assertTrue(timecodeDirectory.getBoolean(Mp4TimecodeDirectory.TAG_DROP_FRAME));
        assertTrue(timecodeDirectory.getBoolean(Mp4TimecodeDirectory.TAG_24_HOUR_MAX));
        assertFalse(timecodeDirectory.getBoolean(Mp4TimecodeDirectory.TAG_COUNTER));
        assertEquals("Bold", timecodeDirectory.getString(Mp4TimecodeDirectory.TAG_TEXT_FACE));
        assertEquals(12, timecodeDirectory.getInt(Mp4TimecodeDirectory.TAG_TEXT_SIZE));
        assertEquals("Arial", timecodeDirectory.getString(Mp4TimecodeDirectory.TAG_FONT_NAME));

        Mp4TextDirectory textDirectory = metadata.getFirstDirectoryOfType(Mp4TextDirectory.class);
        assertNotNull(textDirectory);
        assertTrue(textDirectory.getBoolean(Mp4TextDirectory.TAG_AUTO_SCALE));
        assertEquals("Center", textDirectory.getString(Mp4TextDirectory.TAG_JUSTIFICATION));
        assertEquals(21, textDirectory.getInt(Mp4TextDirectory.TAG_FONT_NUMBER));
        assertEquals("Italic", textDirectory.getString(Mp4TextDirectory.TAG_FONT_FACE));
        assertEquals("Font", textDirectory.getString(Mp4TextDirectory.TAG_NAME));

        Mp4SubtitleDirectory subtitleDirectory = metadata.getFirstDirectoryOfType(Mp4SubtitleDirectory.class);
        assertNotNull(subtitleDirectory);
        assertTrue(subtitleDirectory.getBoolean(Mp4SubtitleDirectory.TAG_SOME_SAMPLES_FORCED));
        assertFalse(subtitleDirectory.getBoolean(Mp4SubtitleDirectory.TAG_ALL_SAMPLES_FORCED));
        assertEquals(7, subtitleDirectory.getInt(Mp4SubtitleDirectory.TAG_FONT_IDENTIFIER));
        assertEquals("Bold", subtitleDirectory.getString(Mp4SubtitleDirectory.TAG_FONT_FACE));
        assertEquals(12, subtitleDirectory.getInt(Mp4SubtitleDirectory.TAG_FONT_SIZE));
        assertNoErrors(metadata);
    }

    @Test
    public void testTheLivePhotoReaderReadsTheStillImageTimeFromTheMetadataTrack() throws Exception
    {
        // the still image time track: one sample of one tick, presented at 880 of 600 ticks a second by the edit list
        byte[] keys = box("keys",
            box(int32(1), box("keyd", ascii("mdta"), ascii("com.apple.quicktime.still-image-time"))),
            box(int32(2), box("keyd", ascii("mdta"), ascii("com.apple.quicktime.live-photo-still-image-transform"))),
            box(int32(3), box("keyd", ascii("mdta"), ascii("com.apple.quicktime.live-photo-still-image-transform-reference-dimensions"))));
        ByteBuffer transform = ByteBuffer.allocate(72);
        for (double value : new double[] {1, 0, 4.5, 0, 1, -4.75, 0, 0, 1})
            transform.putDouble(value);
        byte[] sample = concat(
            concat(int32(9), int32(1), new byte[] {(byte) 0xff}),
            concat(int32(8 + 72), int32(2), transform.array()),
            concat(int32(16), int32(3), ByteBuffer.allocate(8).putFloat(1920f).putFloat(1440f).array()));
        byte[] elst = box("elst", int32(0), int32(2), int32(880), int32(-1), int32(0x10000), int32(1), int32(0), int32(0x10000));
        byte[] mvhd = box("mvhd", int32(0), int32(0), int32(0), int32(600), int32(881), new byte[80]);
        byte[] file = livePhotoFile(mvhd, elst, keys, sample);

        // the default readers give the time from moov alone, without reading the sample
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(file));
        LivePhotoDirectory directory = metadata.getFirstDirectoryOfType(LivePhotoDirectory.class);
        assertNotNull(directory);
        Rational time = directory.getRational(LivePhotoDirectory.TAG_STILL_IMAGE_TIME);
        assertNotNull(time);
        assertEquals(880, time.getNumerator());
        assertEquals(600, time.getDenominator());
        assertEquals("1.467 seconds", directory.getDescription(LivePhotoDirectory.TAG_STILL_IMAGE_TIME));
        assertEquals(1, directory.getTagCount());
        // the metadata track has a directory of its own, as any 'meta' track
        assertNotNull(metadata.getFirstDirectoryOfType(Mp4MetaDirectory.class));
        assertNoErrors(metadata);

        // the transform reader adds the sample's transform and dimensions to the same directory
        List<IsoBoxReader> readers = new ArrayList<IsoBoxReader>(Mp4MetadataReader.ALL_READERS);
        readers.add(Mp4MetadataReader.LIVE_PHOTO_TRANSFORM_READER);
        metadata = Mp4MetadataReader.readMetadata(IsoInput.of(new ByteArrayInputStream(file)), readers);
        directory = metadata.getFirstDirectoryOfType(LivePhotoDirectory.class);
        assertNotNull(directory);
        assertEquals(1, ((Collection<?>) metadata.getDirectoriesOfType(LivePhotoDirectory.class)).size());
        assertEquals("1.467 seconds", directory.getDescription(LivePhotoDirectory.TAG_STILL_IMAGE_TIME));
        assertEquals("1920 x 1440", directory.getDescription(LivePhotoDirectory.TAG_STILL_IMAGE_REFERENCE_DIMENSIONS));
        assertEquals(4.5, ((double[]) directory.getObject(LivePhotoDirectory.TAG_STILL_IMAGE_TRANSFORM))[2], 0d);
        assertNoErrors(metadata);
    }

    @Test
    public void testAStillImageTrackWithoutAnEmptyEditIsTheFirstFrame() throws Exception
    {
        byte[] keys = box("keys", box(int32(1), box("keyd", ascii("mdta"), ascii("com.apple.quicktime.still-image-time"))));
        byte[] elst = box("elst", int32(0), int32(1), int32(1), int32(0), int32(0x10000));
        byte[] mvhd = box("mvhd", int32(0), int32(0), int32(0), int32(600), int32(1), new byte[80]);
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(livePhotoFile(mvhd, elst, keys, new byte[] {0, 0, 0, 9, 0, 0, 0, 1, (byte) 0xff})));

        LivePhotoDirectory directory = metadata.getFirstDirectoryOfType(LivePhotoDirectory.class);
        assertNotNull(directory);
        assertEquals("0.000 seconds", directory.getDescription(LivePhotoDirectory.TAG_STILL_IMAGE_TIME));
        assertNoErrors(metadata);
    }

    @Test
    public void testAStillImageTrackWithSeveralSamplesGivesNoTime() throws Exception
    {
        byte[] keys = box("keys", box(int32(1), box("keyd", ascii("mdta"), ascii("com.apple.quicktime.still-image-time"))));
        byte[] elst = box("elst", int32(0), int32(2), int32(880), int32(-1), int32(0x10000), int32(2), int32(0), int32(0x10000));
        byte[] mvhd = box("mvhd", int32(0), int32(0), int32(0), int32(600), int32(882), new byte[80]);
        // the same file, but the time-to-sample table declares two samples
        byte[] file = livePhotoFile(mvhd, elst, keys, new byte[] {0, 0, 0, 9, 0, 0, 0, 1, (byte) 0xff});
        byte[] stts = box("stts", int32(0), int32(1), int32(1), int32(1));
        byte[] twoSamples = box("stts", int32(0), int32(1), int32(2), int32(1));
        int at = indexOf(file, stts);
        assertTrue(at > 0);
        System.arraycopy(twoSamples, 0, file, at, twoSamples.length);
        Metadata metadata = Mp4MetadataReader.readMetadata(new ByteArrayInputStream(file));

        assertNull(metadata.getFirstDirectoryOfType(LivePhotoDirectory.class));
        assertNoErrors(metadata);
    }

    private static int indexOf(byte[] haystack, byte[] needle)
    {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j])
                j++;
            if (j == needle.length)
                return i;
        }
        return -1;
    }

    /** 'ftyp', a 'moov' with the movie header and one 'mebx' track whose edit list is {@code elst}, then an 'mdat' holding {@code sample}. */
    private static byte[] livePhotoFile(byte[] mvhd, byte[] elst, byte[] keys, byte[] sample)
    {
        byte[] probe = concat(ftyp("qt  "), livePhotoMovie(mvhd, elst, keys, sample.length, 0));
        return concat(ftyp("qt  "), livePhotoMovie(mvhd, elst, keys, sample.length, probe.length + 8), box("mdat", sample));
    }

    private static byte[] livePhotoMovie(byte[] mvhd, byte[] elst, byte[] keys, int sampleSize, int sampleOffset)
    {
        byte[] stsd = box("stsd", int32(0), int32(1), box("mebx", new byte[8], keys));
        byte[] stbl = box("stbl", stsd,
            box("stts", int32(0), int32(1), int32(1), int32(1)),
            box("stsc", int32(0), int32(1), int32(1), int32(1), int32(1)),
            box("stsz", int32(0), int32(sampleSize), int32(1)),
            box("stco", int32(0), int32(1), int32(sampleOffset)));
        byte[] trak = box("trak", box("tkhd", int32(0), int32(0), int32(0), int32(3), new byte[72]), box("edts", elst),
            box("mdia", mediaHeader(EPOCH_1904, EPOCH_1904, 600, 1), handler("meta"), box("minf", stbl)));
        return box("moov", mvhd, trak);
    }

    private static void assertNoErrors(Metadata metadata)
    {
        for (Directory directory : metadata.getDirectories())
            assertFalse(directory.getName() + ": " + directory.getErrors(), directory.hasErrors());
    }

    private static byte[] ftyp(String brand)
    {
        return box("ftyp", ascii(brand), int32(0), ascii(brand));
    }

    /**
     * A media handler 'hdlr' whose component subtype is {@code subtype}: the subtype names the
     * media or metadata kind, {@code vide}, {@code soun}, {@code mdta}, and so on; then three
     * reserved words and an empty name.
     */
    private static byte[] handler(String subtype)
    {
        return box("hdlr", int32(0), ascii("mhlr"), ascii(subtype), new byte[12], new byte[] {0});
    }

    /** An 'mdhd' of version 0. */
    private static byte[] mediaHeader(long creation, long modification, long timeScale, long duration)
    {
        return box("mdhd", int32(0), int32(creation), int32(modification), int32(timeScale), int32(duration), int16(0), int16(0));
    }

    /** A 'keys' entry: its size, the 'mdta' namespace and the key. */
    private static byte[] key(String key)
    {
        byte[] name = ascii(key);
        return concat(int32(8 + name.length), ascii("mdta"), name);
    }

    private static byte[] box(String type, byte[]... payload)
    {
        return box(ascii(type), payload);
    }

    private static byte[] box(byte[] type, byte[]... payload)
    {
        byte[] body = concat(payload);
        return concat(int32(8 + body.length), type, body);
    }

    private static byte[] int32(long value)
    {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] int16(int value)
    {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    private static byte[] ascii(String value)
    {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts)
            out.write(part, 0, part.length);
        return out.toByteArray();
    }
}
