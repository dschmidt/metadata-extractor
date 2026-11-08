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
package com.drew.imaging.quicktime;

import com.drew.imaging.isobmff.IsoInput;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeMediaDirectory;
import com.drew.metadata.mov.media.QuickTimeSoundDirectory;
import com.drew.metadata.mov.media.QuickTimeTextDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("deprecation")
public class QuickTimeDirectoriesTest
{
    /** Seconds from 1904 to the Unix epoch, the base of QuickTime's times. */
    private static final long EPOCH_1904 = 2082844800L;

    @Test
    public void testKeysAndValuesFillTheMetadataDirectory() throws Exception
    {
        byte[] meta = box("meta",
            handler("mdta"),
            box("keys", int32(0), int32(2), key("com.apple.quicktime.make"), key("com.apple.quicktime.model")),
            box("ilst",
                box(int32(1), box("data", int32(1), int32(0), ascii("Apple"))),
                box(int32(2), box("data", int32(1), int32(0), ascii("iPhone 14 Pro"))),
                box(int32(3), box("data", int32(1), int32(0), ascii("no key for this index")))));
        Metadata metadata = QuickTimeMetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp(), box("moov", meta))));

        QuickTimeMetadataDirectory directory = metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class);
        assertNotNull(directory);
        assertEquals("Apple", directory.getString(QuickTimeMetadataDirectory.TAG_MAKE));
        assertEquals("iPhone 14 Pro", directory.getString(QuickTimeMetadataDirectory.TAG_MODEL));
        assertEquals(2, directory.getTagCount());
        assertNoErrors(metadata);
    }

    @Test
    public void testIntegerAndDoubleValueTypesAreRead() throws Exception
    {
        // 21: a signed integer of 2 bytes; 77: an unsigned 32-bit; 24: a double; 78: an unsigned 64-bit
        byte[] meta = box("meta",
            handler("mdta"),
            box("keys", int32(0), int32(4), key("com.apple.quicktime.rating.user"), key("com.apple.quicktime.direction.facing"),
                key("com.apple.quicktime.direction.motion"), key("com.apple.quicktime.pixeldensity")),
            box("ilst",
                box(int32(1), box("data", int32(21), int32(0), int16(-2))),
                box(int32(2), box("data", int32(77), int32(0), int32(0xFFFFFFF0L))),
                box(int32(3), box("data", int32(24), int32(0), ByteBuffer.allocate(8).putDouble(26.5).array())),
                box(int32(4), box("data", int32(78), int32(0), int32(1), int32(2)))));
        Metadata metadata = QuickTimeMetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp(), box("moov", meta))));

        QuickTimeMetadataDirectory directory = metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class);
        assertNotNull(directory);
        assertEquals(-2, directory.getInt(QuickTimeMetadataDirectory.getTagType("com.apple.quicktime.rating.user")));
        assertEquals(0xFFFFFFF0L, directory.getLong(QuickTimeMetadataDirectory.getTagType("com.apple.quicktime.direction.facing")));
        assertEquals(26.5, directory.getDouble(QuickTimeMetadataDirectory.getTagType("com.apple.quicktime.direction.motion")), 0d);
        assertEquals((1L << 32) + 2, directory.getLong(QuickTimeMetadataDirectory.getTagType("com.apple.quicktime.pixeldensity")));
        assertNoErrors(metadata);
    }

    @Test
    public void testITunesListFillsTheMetadataDirectory() throws Exception
    {
        // 'udta/meta' is a FullBox, unlike QuickTime's 'moov/meta'
        byte[] meta = box("meta", int32(0),
            handler("mdir"),
            box("ilst", box("aART", box("data", int32(1), int32(0), ascii("The Artist")))));
        Metadata metadata = QuickTimeMetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp(), box("moov", box("udta", meta)))));

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
        Metadata metadata = QuickTimeMetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp(), box("moov", meta))));

        assertNull(metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class));
        assertNoErrors(metadata);
    }

    @Test
    public void testTheHandlerChoosesTheMediaDirectoryAndTheMediaHeaderDatesIt() throws Exception
    {
        long creation = EPOCH_1904 + 86400;
        byte[] video = box("trak", box("mdia",
            mediaHeader(creation, creation + 60, 600, 6000),
            handler("vide"),
            box("minf", box("stbl",
                // one entry: 300 samples of 20 ticks each, at a time scale of 600 that is 30 frames per second
                box("stts", int32(0), int32(1), int32(300), int32(20))))));
        byte[] sound = box("trak", box("mdia",
            mediaHeader(creation, creation + 60, 44100, 441000),
            handler("soun"),
            box("minf", box("stbl", box("stts", int32(0), int32(1), int32(441000), int32(1))))));
        byte[] other = box("trak", box("mdia", mediaHeader(creation, creation, 600, 1), handler("meta")));
        Metadata metadata = QuickTimeMetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp(), box("moov", video, sound, other))));

        QuickTimeVideoDirectory videoDirectory = metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class);
        assertNotNull(videoDirectory);
        assertEquals(86400 * 1000L, videoDirectory.getDate(QuickTimeMediaDirectory.TAG_CREATION_TIME).getTime());
        assertEquals((86400 + 60) * 1000L, videoDirectory.getDate(QuickTimeMediaDirectory.TAG_MODIFICATION_TIME).getTime());
        assertEquals(30f, videoDirectory.getFloat(QuickTimeVideoDirectory.TAG_FRAME_RATE), 0f);

        QuickTimeSoundDirectory soundDirectory = metadata.getFirstDirectoryOfType(QuickTimeSoundDirectory.class);
        assertNotNull(soundDirectory);
        assertEquals(44100d, soundDirectory.getDouble(QuickTimeSoundDirectory.TAG_AUDIO_SAMPLE_RATE), 0d);

        // the file directory, the video and the sound directory; the 'meta' track has no directory of its own
        assertEquals(3, ((Collection<?>) metadata.getDirectories()).size());
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
        Metadata metadata = QuickTimeMetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp(), box("moov", video, sound, text))));

        assertEquals(1000 * 1000L, metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class).getDate(QuickTimeMediaDirectory.TAG_CREATION_TIME).getTime());
        QuickTimeSoundDirectory soundDirectory = metadata.getFirstDirectoryOfType(QuickTimeSoundDirectory.class);
        assertEquals(2000 * 1000L, soundDirectory.getDate(QuickTimeMediaDirectory.TAG_CREATION_TIME).getTime());
        assertEquals(48000d, soundDirectory.getDouble(QuickTimeSoundDirectory.TAG_AUDIO_SAMPLE_RATE), 0d);
        assertNull(metadata.getFirstDirectoryOfType(QuickTimeTextDirectory.class).getDate(QuickTimeMediaDirectory.TAG_CREATION_TIME));
        assertNoErrors(metadata);
    }

    /** The old reader took the frame rate from the first 'stts' entry alone, and the MP4 directories are not handed out. */
    @Test
    public void testTheFrameRateComesFromTheFirstEntryAndOnlyQuickTimeDirectoriesRemain() throws Exception
    {
        byte[] video = box("trak", box("mdia", mediaHeader(EPOCH_1904, EPOCH_1904, 600, 1200), handler("vide"),
            // 2 frames of 600 ticks, then 10 of 60: the average is 10 a second, the first entry says 1
            box("minf", box("stbl", box("stts", int32(0), int32(2), int32(2), int32(600), int32(10), int32(60))))));
        byte[] hint = box("trak", box("mdia", mediaHeader(EPOCH_1904, EPOCH_1904, 600, 1200), handler("hint")));
        Metadata metadata = QuickTimeMetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp(), box("moov", video, hint))));

        assertEquals(1f, metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class).getFloat(QuickTimeVideoDirectory.TAG_FRAME_RATE), 0f);
        for (Directory directory : metadata.getDirectories())
            assertTrue(directory.getName(), directory.getName().startsWith("QuickTime"));
        assertEquals(2, ((Collection<?>) metadata.getDirectories()).size());
        assertNoErrors(metadata);
    }

    /** Sony's XAVC files carry a QuickTime brand but write 'hdlr' in the ISO form, with a NUL-terminated name. */
    @Test
    public void testANulTerminatedHandlerNameChoosesTheMediaDirectoryToo() throws Exception
    {
        byte[] hdlr = box("hdlr", int32(0), new byte[4], ascii("vide"), new byte[12], ascii("Video Media Handler"), new byte[] {0});
        byte[] trak = box("trak", box("mdia", mediaHeader(EPOCH_1904, EPOCH_1904, 600, 600), hdlr));
        Metadata metadata = QuickTimeMetadataReader.readMetadata(new ByteArrayInputStream(concat(ftyp(), box("moov", trak))));

        assertNotNull(metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class));
        assertNoErrors(metadata);
    }

    @Test
    public void testAFileIsReadLikeAStream() throws Exception
    {
        byte[] bytes = concat(ftyp(), box("moov", box("meta", handler("mdta"),
            box("keys", int32(0), int32(1), key("com.apple.quicktime.software")),
            box("ilst", box(int32(1), box("data", int32(1), int32(0), ascii("17.0")))))));
        File file = File.createTempFile("movie", ".mov");
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(bytes);
            out.close();
            Metadata metadata = QuickTimeMetadataReader.readMetadata(IsoInput.of(file));
            assertEquals("17.0", metadata.getFirstDirectoryOfType(QuickTimeMetadataDirectory.class).getString(QuickTimeMetadataDirectory.TAG_SOFTWARE));
            assertEquals("qt  ", metadata.getFirstDirectoryOfType(QuickTimeDirectory.class).getString(QuickTimeDirectory.TAG_MAJOR_BRAND));
            assertNoErrors(metadata);
        } finally {
            assertTrue(file.delete());
        }
    }

    private static void assertNoErrors(Metadata metadata)
    {
        for (Directory directory : metadata.getDirectories())
            assertFalse(directory.getName() + ": " + directory.getErrors(), directory.hasErrors());
    }

    private static byte[] ftyp()
    {
        return box("ftyp", ascii("qt  "), int32(0), ascii("qt  "));
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
