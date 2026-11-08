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
import com.drew.imaging.isobmff.IsoBoxLayout;
import com.drew.imaging.isobmff.IsoStandardLayout;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.isobmff.IsoLanguageCode;
import com.drew.imaging.isobmff.QuickTimeLayout;
import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegProcessingException;
import com.drew.lang.ByteArrayReader;
import com.drew.lang.ByteTrie;
import com.drew.lang.DateUtil;
import com.drew.lang.Rational;
import com.drew.lang.SequentialByteArrayReader;
import com.drew.lang.SequentialReader;
import com.drew.lang.StringUtil;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.iptc.IptcReader;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.mp4.LivePhotoDirectory;
import com.drew.metadata.mp4.Mp4Dictionary;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4HintDirectory;
import com.drew.metadata.mp4.media.Mp4MediaDirectory;
import com.drew.metadata.mp4.media.Mp4MetaDirectory;
import com.drew.metadata.mp4.media.Mp4MusicDirectory;
import com.drew.metadata.mp4.media.Mp4SoundDirectory;
import com.drew.metadata.mp4.media.Mp4SubtitleDirectory;
import com.drew.metadata.mp4.media.Mp4TextDirectory;
import com.drew.metadata.mp4.media.Mp4TimecodeDirectory;
import com.drew.metadata.mp4.media.Mp4UuidBoxDirectory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import com.drew.metadata.photoshop.PhotoshopReader;
import com.drew.metadata.xmp.XmpReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The box layout and readers of MP4 and QuickTime files. The layout is the standard one with
 * QuickTime's deviations; the readers fill {@link Mp4Directory}, one {@link Mp4MediaDirectory}
 * per track, one {@link Mp4UuidBoxDirectory} per {@code uuid} box, one
 * {@link QuickTimeMetadataDirectory} per {@code meta} with keys or an iTunes-style list, and a
 * {@link LivePhotoDirectory} for an Apple Live Photo's movie.
 */
final class Mp4Readers
{
    private static final String TRACK = "moov/trak/";
    private static final String MEDIA = TRACK + "mdia/";
    private static final String MEDIA_INFO = MEDIA + "minf/";
    private static final String SAMPLE_TABLE = MEDIA_INFO + "stbl/";
    private static final String[] USER_DATA = {"udta/", "moov/udta/", TRACK + "udta/"};
    /** Where file-level atoms such as {@code XMP_} and Canon's {@code CNTH} are found. */
    private static final String[] GENERAL = {"", "moov/", "moov/udta/", TRACK, TRACK + "udta/", MEDIA, "moov/meta/", "moov/udta/meta/", TRACK + "meta/"};
    /** Where a {@code meta} with keys or an iTunes-style list can be. */
    private static final String[] META = {"meta/", "moov/meta/", "moov/udta/meta/", TRACK + "meta/", TRACK + "udta/meta/"};

    private static final String HANDLER_METADATA_DIRECTORY = "mdir";
    private static final String HANDLER_METADATA_DATA = "mdta";
    private static final String KEY_LOCATION = "com.apple.quicktime.location.ISO6709";
    /** iTunes list atoms whose implicit-typed value is a small integer: flags, counts and IDs. */
    private static final List<String> IMPLICIT_INTEGER_KEYS = Arrays.asList(
        "cpil", "pgap", "pcst", "hdvd", "shwm", "stik", "rtng", "akID", "sfID", "atID", "cnID", "geID", "plID", "cmID", "tmpo", "tvsn", "tves");
    /** Sample entry types of protected tracks, whose original format is in {@code sinf/frma}. */
    private static final List<String> PROTECTED_FORMATS = Arrays.asList("drms", "drmi", "enca", "encv", "enct", "encs");

    private Mp4Readers() {}

    /** The standard layout with QuickTime's deviations, which MP4 files share; see {@link QuickTimeLayout}. */
    static final IsoBoxLayout LAYOUT = QuickTimeLayout.INSTANCE;

    static final IsoBoxReader USER_DATA_READER = new IsoBoxReader()
    {
        @NotNull
        @Override
        public Iterable<String> getBoxPaths()
        {
            List<String> paths = new ArrayList<String>();
            for (String udta : USER_DATA) {
                paths.add(udta + "©xyz");
                paths.add(udta + "meta/ilst/©nam/data");
                paths.add(udta + "meta/ilst/©cmt/data");
                paths.add(udta + "Xtra");
            }
            return paths;
        }

        @Override
        public void readBox(@NotNull IsoBoxContext box) throws IOException
        {
            Mp4Directory directory = directory(box);
            SequentialReader payload = box.payload();
            String type = box.box().type;
            if (type.equals("©xyz")) {
                int length = payload.getUInt16();
                payload.skip(2);
                readLocation(payload.getString(length, "UTF-8"), directory);
            } else if (type.equals("data")) {
                if (box.box().payloadSize <= 8)
                    return;
                payload.skip(8);
                String value = payload.getString((int) box.box().payloadSize - 8, "UTF-8");
                boolean title = box.parent().box().type.equals("©nam");
                directory.setString(title ? Mp4Directory.TAG_TITLE : Mp4Directory.TAG_COMMENT, value);
            } else {
                readXtra(payload, box.box().payloadSize, directory);
            }
        }
    };

    static final List<IsoBoxReader> ALL_READERS = Collections.unmodifiableList(Arrays.asList(
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readFileType(box);
            }
        }, "ftyp"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readMovieHeader(box.payload(), directory(box));
            }
        }, "moov/mvhd"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readTrackHeader(box.payload(), directory(box));
            }
        }, TRACK + "tkhd"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                box.parent().put(MediaHeader.class, MediaHeader.read(box.payload()));
            }
        }, MEDIA + "mdhd"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box)
            {
                String handlerType = IsoStandardLayout.handlerType(box);
                if ("meta".equals(handlerType))
                    timedMetadata(box);
                final Mp4MediaDirectory media = mediaDirectory(handlerType);
                if (media == null)
                    return;
                box.metadata().addDirectory(media);
                IsoBoxContext mdia = box.parent();
                mdia.put(Mp4MediaDirectory.class, media);
                // the track's own 'mdhd' dates it, whichever of the two comes first
                mdia.whenDone(new IsoBoxReaders.Callback()
                {
                    @Override
                    public void read(@NotNull IsoBoxContext done)
                    {
                        MediaHeader header = done.get(MediaHeader.class);
                        if (header != null)
                            header.apply(media);
                    }
                });
            }
        }, MEDIA + "hdlr"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readMediaInformation(box);
            }
        }, MEDIA_INFO + "vmhd", MEDIA_INFO + "smhd", MEDIA_INFO + "hmhd", MEDIA_INFO + "gmhd/tmcd/tcmi", MEDIA_INFO + "gmhd/tcmi"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readSampleDescription(box);
            }
        }, SAMPLE_TABLE + "stsd"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readTimeToSample(box);
            }
        }, SAMPLE_TABLE + "stts"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readSampleEntryBox(box);
            }
        }, SAMPLE_TABLE + "stsd/*/esds", SAMPLE_TABLE + "stsd/*/wave/esds", SAMPLE_TABLE + "stsd/*/btrt",
           SAMPLE_TABLE + "stsd/*/sinf/frma", SAMPLE_TABLE + "stsd/*/sinf/schm"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readEditList(box);
            }
        }, TRACK + "edts/elst"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readUuid(box);
            }
        }, "uuid", "moov/uuid", TRACK + "uuid"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                new XmpReader().extract(box.payloadBytes(), box.metadata(), directory(box));
            }
        }, paths(GENERAL, "XMP_")),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readCanonThumbnail(box);
            }
        }, paths(GENERAL, "CNTH")),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readMetadataHandler(box);
            }
        }, paths(META, "hdlr")),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readKeys(box);
            }
        }, paths(META, "keys")),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readData(box);
            }
        }, paths(META, "ilst/*/data")),
        USER_DATA_READER));

    private static String[] paths(String[] prefixes, String type)
    {
        String[] paths = new String[prefixes.length];
        for (int i = 0; i < prefixes.length; i++)
            paths[i] = prefixes[i] + type;
        return paths;
    }

    private static IsoBoxReader on(IsoBoxReaders.Callback callback, String... paths)
    {
        return IsoBoxReaders.on(callback, paths);
    }

    @NotNull
    private static Mp4Directory directory(@NotNull IsoBoxContext box)
    {
        return box.root().get(Mp4Directory.class);
    }

    private static void readFileType(@NotNull IsoBoxContext box) throws IOException
    {
        SequentialReader reader = box.payload();
        Mp4Directory directory = directory(box);
        directory.setString(Mp4Directory.TAG_MAJOR_BRAND, reader.getString(4));
        directory.setLong(Mp4Directory.TAG_MINOR_VERSION, reader.getUInt32());
        List<String> brands = new ArrayList<String>();
        for (long i = 8; i + 4 <= box.box().payloadSize; i += 4)
            brands.add(reader.getString(4));
        directory.setStringArray(Mp4Directory.TAG_COMPATIBLE_BRANDS, brands.toArray(new String[0]));
    }

    private static void readMovieHeader(@NotNull SequentialReader reader, @NotNull Mp4Directory directory) throws IOException
    {
        // ISO/IED 14496-12:2015 pg.23
        short version = reader.getUInt8();
        reader.skip(3);
        long creationTime = version == 1 ? reader.getInt64() : reader.getUInt32();
        long modificationTime = version == 1 ? reader.getInt64() : reader.getUInt32();
        long timescale = reader.getUInt32();
        long duration = version == 1 ? reader.getInt64() : reader.getUInt32();
        int rate = reader.getInt32();
        int volume = reader.getInt16();
        reader.skip(10);
        int[] matrix = new int[9];
        for (int i = 0; i < 9; i++)
            matrix[i] = reader.getInt32();
        // pre-defined in ISO/IEC 14496-12; QuickTime's preview, poster, selection and current times
        long previewTime = reader.getUInt32();
        long previewDuration = reader.getUInt32();
        long posterTime = reader.getUInt32();
        long selectionTime = reader.getUInt32();
        long selectionDuration = reader.getUInt32();
        long currentTime = reader.getUInt32();
        long nextTrackID = reader.getUInt32();

        directory.setDate(Mp4Directory.TAG_CREATION_TIME, DateUtil.get1Jan1904EpochDate(creationTime));
        directory.setDate(Mp4Directory.TAG_MODIFICATION_TIME, DateUtil.get1Jan1904EpochDate(modificationTime));
        directory.setLong(Mp4Directory.TAG_DURATION, duration);
        directory.setLong(Mp4Directory.TAG_TIME_SCALE, timescale);
        directory.setRational(Mp4Directory.TAG_DURATION_SECONDS, new Rational(duration, timescale));
        directory.setIntArray(Mp4Directory.TAG_TRANSFORMATION_MATRIX, matrix);
        directory.setDouble(Mp4Directory.TAG_PREFERRED_RATE, ((rate & 0xFFFF0000) >> 16) + (rate & 0x0000FFFF) / Math.pow(2, 4));
        directory.setDouble(Mp4Directory.TAG_PREFERRED_VOLUME, ((volume & 0xFF00) >> 8) + (volume & 0x00FF) / Math.pow(2, 2));
        directory.setLong(Mp4Directory.TAG_PREVIEW_TIME, previewTime);
        directory.setLong(Mp4Directory.TAG_PREVIEW_DURATION, previewDuration);
        directory.setLong(Mp4Directory.TAG_POSTER_TIME, posterTime);
        directory.setLong(Mp4Directory.TAG_SELECTION_TIME, selectionTime);
        directory.setLong(Mp4Directory.TAG_SELECTION_DURATION, selectionDuration);
        directory.setLong(Mp4Directory.TAG_CURRENT_TIME, currentTime);
        directory.setLong(Mp4Directory.TAG_NEXT_TRACK_ID, nextTrackID);
    }

    private static void readTrackHeader(@NotNull SequentialReader reader, @NotNull Mp4Directory directory) throws IOException
    {
        // ISO/IED 14496-12:2005 pg.17-18
        int version = reader.getUInt8();
        reader.skip(3);
        reader.skip(version == 1 ? 32 : 20);
        reader.skip(16);
        int[] matrix = new int[9];
        for (int i = 0; i < 9; i++)
            matrix[i] = reader.getInt32();
        long width = reader.getInt32();
        long height = reader.getInt32();
        if (width != 0 && height != 0 && directory.getDoubleObject(Mp4Directory.TAG_ROTATION) == null) {
            double theta = Math.atan2(matrix[0] + matrix[3], matrix[1] + matrix[4]);
            directory.setDouble(Mp4Directory.TAG_ROTATION, Math.toDegrees(theta) - 45);
        }
    }

    @Nullable
    private static Mp4MediaDirectory mediaDirectory(@Nullable String handlerType)
    {
        if ("soun".equals(handlerType))
            return new Mp4SoundDirectory();
        if ("vide".equals(handlerType))
            return new Mp4VideoDirectory();
        if ("hint".equals(handlerType))
            return new Mp4HintDirectory();
        if ("text".equals(handlerType))
            return new Mp4TextDirectory();
        if ("meta".equals(handlerType))
            return new Mp4MetaDirectory();
        if ("tmcd".equals(handlerType))
            return new Mp4TimecodeDirectory();
        if ("sbtl".equals(handlerType))
            return new Mp4SubtitleDirectory();
        if ("musi".equals(handlerType))
            return new Mp4MusicDirectory();
        return null;
    }

    private static void readMediaInformation(@NotNull IsoBoxContext box) throws IOException
    {
        Mp4MediaDirectory media = box.get(Mp4MediaDirectory.class);
        SequentialReader reader = box.payload();
        String type = box.box().type;
        if (media instanceof Mp4TimecodeDirectory && type.equals("tcmi")) {
            // QuickTime File Format: timecode media information atom
            reader.skip(4);
            media.setInt(Mp4TimecodeDirectory.TAG_TEXT_FONT, reader.getInt16());
            String face = fontFace(reader.getInt16());
            if (face != null)
                media.setString(Mp4TimecodeDirectory.TAG_TEXT_FACE, face);
            media.setInt(Mp4TimecodeDirectory.TAG_TEXT_SIZE, reader.getInt16());
            reader.skip(2);
            media.setIntArray(Mp4TimecodeDirectory.TAG_TEXT_COLOR, new int[] {reader.getUInt16(), reader.getUInt16(), reader.getUInt16()});
            media.setIntArray(Mp4TimecodeDirectory.TAG_BACKGROUND_COLOR, new int[] {reader.getUInt16(), reader.getUInt16(), reader.getUInt16()});
            media.setString(Mp4TimecodeDirectory.TAG_FONT_NAME, reader.getString(reader.getUInt8()));
        } else if (media instanceof Mp4VideoDirectory && type.equals("vmhd")) {
            // ISO/IED 14496-12:2015 pg.155
            reader.skip(4);
            int graphicsMode = reader.getUInt16();
            media.setIntArray(Mp4VideoDirectory.TAG_OPCOLOR, new int[]{reader.getUInt16(), reader.getUInt16(), reader.getUInt16()});
            media.setInt(Mp4VideoDirectory.TAG_GRAPHICS_MODE, graphicsMode);
        } else if (media instanceof Mp4SoundDirectory && type.equals("smhd")) {
            // ISO/IED 14496-12:2015 pg.159
            reader.skip(4);
            int balance = reader.getInt16();
            media.setDouble(Mp4SoundDirectory.TAG_SOUND_BALANCE, (balance & 0xFFFF0000) + (balance & 0x0000FFFF) / Math.pow(2, 4));
        } else if (media instanceof Mp4HintDirectory && type.equals("hmhd")) {
            // ISO/IED 14496-12:2015 pg.169
            reader.skip(4);
            media.setInt(Mp4HintDirectory.TAG_MAX_PDU_SIZE, reader.getUInt16());
            media.setInt(Mp4HintDirectory.TAG_AVERAGE_PDU_SIZE, reader.getUInt16());
            media.setLong(Mp4HintDirectory.TAG_MAX_BITRATE, reader.getUInt32());
            media.setLong(Mp4HintDirectory.TAG_AVERAGE_BITRATE, reader.getUInt32());
        }
    }

    /** The QuickTime style of a font face value, or {@code null} for plain. */
    @Nullable
    private static String fontFace(int face)
    {
        switch (face) {
            case 0x0001: return "Bold";
            case 0x0002: return "Italic";
            case 0x0004: return "Underline";
            case 0x0008: return "Outline";
            case 0x0010: return "Shadow";
            case 0x0020: return "Condense";
            case 0x0040: return "Extend";
            default: return null;
        }
    }

    /** Reads the first entry of a track's {@code stsd}, for the kinds of track with a directory of their own. */
    private static void readSampleDescription(@NotNull IsoBoxContext box) throws IOException
    {
        Mp4MediaDirectory media = box.get(Mp4MediaDirectory.class);
        if (media instanceof Mp4MetaDirectory) {
            TimedMetadata timed = box.get(TimedMetadata.class);
            if (timed != null)
                timed.stillImageTime = LivePhotoSampleReader.keys(sampleEntry(box)).containsValue(LivePhotoSampleReader.KEY_STILL_IMAGE_TIME);
            return;
        }
        if (media == null || media instanceof Mp4HintDirectory || media instanceof Mp4MusicDirectory)
            return;
        SequentialReader reader = box.payload();
        // ISO/IED 14496-12:2015 pg.33: version and flags, entry count, entry size, format
        reader.skip(4);
        if (reader.getUInt32() == 0)
            return;
        reader.skip(4);
        String format = reader.getString(4);
        reader.skip(8);
        // a protected entry names its scheme in sinf/schm, which overrides the entry type set here
        if (PROTECTED_FORMATS.contains(format))
            media.setString(Mp4MediaDirectory.TAG_PROTECTION_SCHEME, format);
        if (media instanceof Mp4TextDirectory) {
            readTextSampleEntry(reader, (Mp4TextDirectory) media);
            return;
        }
        if (media instanceof Mp4SubtitleDirectory) {
            readSubtitleSampleEntry(reader, (Mp4SubtitleDirectory) media);
            return;
        }
        if (media instanceof Mp4TimecodeDirectory) {
            // QuickTime File Format: timecode sample description, after 4 reserved bytes
            reader.skip(4);
            int flags = reader.getInt32();
            media.setBoolean(Mp4TimecodeDirectory.TAG_DROP_FRAME, (flags & 0x0001) != 0);
            media.setBoolean(Mp4TimecodeDirectory.TAG_24_HOUR_MAX, (flags & 0x0002) != 0);
            media.setBoolean(Mp4TimecodeDirectory.TAG_NEGATIVE_TIMES_OK, (flags & 0x0004) != 0);
            media.setBoolean(Mp4TimecodeDirectory.TAG_COUNTER, (flags & 0x0008) != 0);
            return;
        }
        if (media instanceof Mp4SoundDirectory) {
            // ISO/IED 14496-12:2015 pg.161
            reader.skip(8);
            int channelCount = reader.getUInt16();
            int sampleSize = reader.getInt16();
            Mp4Dictionary.setLookup(Mp4SoundDirectory.TAG_AUDIO_FORMAT, format, media);
            media.setString(Mp4SoundDirectory.TAG_AUDIO_FORMAT_RAW, format);
            media.setInt(Mp4SoundDirectory.TAG_NUMBER_OF_CHANNELS, channelCount);
            media.setInt(Mp4SoundDirectory.TAG_AUDIO_SAMPLE_SIZE, sampleSize);
            return;
        }
        // ISO/IED 14496-12:2015 pg.156; the pre-defined fields are QuickTime's vendor, qualities and color table
        reader.skip(4);
        String vendor = reader.getString(4);
        long temporalQuality = reader.getUInt32();
        long spatialQuality = reader.getUInt32();
        int width = reader.getUInt16();
        int height = reader.getUInt16();
        long horizontalResolution = reader.getUInt32();
        long verticalResolution = reader.getUInt32();
        reader.skip(6);
        String compressorName = reader.getString(32).trim();
        int depth = reader.getUInt16();
        int colorTable = reader.getInt16();
        Mp4Dictionary.setLookup(Mp4VideoDirectory.TAG_VENDOR, vendor, media);
        media.setLong(Mp4VideoDirectory.TAG_TEMPORAL_QUALITY, temporalQuality);
        media.setLong(Mp4VideoDirectory.TAG_SPATIAL_QUALITY, spatialQuality);
        Mp4Dictionary.setLookup(Mp4VideoDirectory.TAG_COMPRESSION_TYPE, format, media);
        media.setString(Mp4VideoDirectory.TAG_COMPRESSION_TYPE_RAW, format);
        media.setInt(Mp4VideoDirectory.TAG_WIDTH, width);
        media.setInt(Mp4VideoDirectory.TAG_HEIGHT, height);
        if (!compressorName.isEmpty())
            media.setString(Mp4VideoDirectory.TAG_COMPRESSOR_NAME, compressorName);
        media.setInt(Mp4VideoDirectory.TAG_DEPTH, depth);
        media.setInt(Mp4VideoDirectory.TAG_COLOR_TABLE, colorTable);
        media.setDouble(Mp4VideoDirectory.TAG_HORIZONTAL_RESOLUTION, fixedPoint(horizontalResolution));
        media.setDouble(Mp4VideoDirectory.TAG_VERTICAL_RESOLUTION, fixedPoint(verticalResolution));
    }

    /** QuickTime File Format: text sample description. */
    private static void readTextSampleEntry(@NotNull SequentialReader reader, @NotNull Mp4TextDirectory text) throws IOException
    {
        int displayFlags = reader.getInt32();
        int justification = reader.getInt32();
        text.setBoolean(Mp4TextDirectory.TAG_AUTO_SCALE, (displayFlags & 0x0002) != 0);
        text.setBoolean(Mp4TextDirectory.TAG_MOVIE_BACKGROUND_COLOR, (displayFlags & 0x0008) != 0);
        text.setBoolean(Mp4TextDirectory.TAG_SCROLL_IN, (displayFlags & 0x0020) != 0);
        text.setBoolean(Mp4TextDirectory.TAG_SCROLL_OUT, (displayFlags & 0x0040) != 0);
        text.setString(Mp4TextDirectory.TAG_HORIZONTAL_SCROLL, (displayFlags & 0x0080) != 0 ? "Horizontal" : "Vertical");
        text.setString(Mp4TextDirectory.TAG_REVERSE_SCROLL, (displayFlags & 0x0100) != 0 ? "Reverse" : "Normal");
        text.setBoolean(Mp4TextDirectory.TAG_CONTINUOUS_SCROLL, (displayFlags & 0x0200) != 0);
        text.setBoolean(Mp4TextDirectory.TAG_DROP_SHADOW, (displayFlags & 0x1000) != 0);
        text.setBoolean(Mp4TextDirectory.TAG_ANTI_ALIAS, (displayFlags & 0x2000) != 0);
        text.setBoolean(Mp4TextDirectory.TAG_KEY_TEXT, (displayFlags & 0x4000) != 0);
        if (justification == 0)
            text.setString(Mp4TextDirectory.TAG_JUSTIFICATION, "Left");
        else if (justification == 1)
            text.setString(Mp4TextDirectory.TAG_JUSTIFICATION, "Center");
        else if (justification == -1)
            text.setString(Mp4TextDirectory.TAG_JUSTIFICATION, "Right");
        text.setIntArray(Mp4TextDirectory.TAG_BACKGROUND_COLOR, new int[] {reader.getUInt16(), reader.getUInt16(), reader.getUInt16()});
        text.setLong(Mp4TextDirectory.TAG_DEFAULT_TEXT_BOX, reader.getInt64());
        reader.skip(8);
        text.setInt(Mp4TextDirectory.TAG_FONT_NUMBER, reader.getUInt16());
        String face = fontFace(reader.getUInt16());
        if (face != null)
            text.setString(Mp4TextDirectory.TAG_FONT_FACE, face);
        reader.skip(3);
        text.setIntArray(Mp4TextDirectory.TAG_FOREGROUND_COLOR, new int[] {reader.getUInt16(), reader.getUInt16(), reader.getUInt16()});
        text.setString(Mp4TextDirectory.TAG_NAME, reader.getString(reader.getUInt8()));
    }

    /** QuickTime File Format: subtitle sample description; its {@code ftab} is not read. */
    private static void readSubtitleSampleEntry(@NotNull SequentialReader reader, @NotNull Mp4SubtitleDirectory subtitle) throws IOException
    {
        int displayFlags = reader.getInt32();
        reader.skip(6);
        long defaultTextBox = reader.getInt64();
        reader.skip(4);
        int fontIdentifier = reader.getInt16();
        int fontFace = reader.getInt8();
        int fontSize = reader.getInt8();
        subtitle.setBoolean(Mp4SubtitleDirectory.TAG_VERTICAL_PLACEMENT, (displayFlags & 0x20000000) != 0);
        subtitle.setBoolean(Mp4SubtitleDirectory.TAG_SOME_SAMPLES_FORCED, (displayFlags & 0x40000000) != 0);
        subtitle.setBoolean(Mp4SubtitleDirectory.TAG_ALL_SAMPLES_FORCED, (displayFlags & 0xC0000000) == 0xC0000000);
        subtitle.setLong(Mp4SubtitleDirectory.TAG_DEFAULT_TEXT_BOX, defaultTextBox);
        subtitle.setInt(Mp4SubtitleDirectory.TAG_FONT_IDENTIFIER, fontIdentifier);
        String face = fontFace(fontFace);
        if (face != null && fontFace <= 0x0004)
            subtitle.setString(Mp4SubtitleDirectory.TAG_FONT_FACE, face);
        subtitle.setInt(Mp4SubtitleDirectory.TAG_FONT_SIZE, fontSize);
        subtitle.setIntArray(Mp4SubtitleDirectory.TAG_FOREGROUND_COLOR, new int[] {reader.getUInt16(), reader.getUInt16(), reader.getUInt16()});
    }

    private static double fixedPoint(long value)
    {
        return ((value & 0xFFFF0000) >> 16) + (value & 0xFFFF) / Math.pow(2, 4);
    }

    /**
     * The sample rate of a sound track, the frame rate of a video track, and the sample count of
     * a timed metadata track. The frame rate is the average over the media's duration, as
     * ExifTool and ffprobe report it; the most common sample duration would misstate tracks
     * timed in milliseconds, where 30 frames a second alternate between 33 and 34.
     */
    private static void readTimeToSample(@NotNull IsoBoxContext box) throws IOException
    {
        Mp4MediaDirectory media = box.get(Mp4MediaDirectory.class);
        MediaHeader header = box.get(MediaHeader.class);
        SequentialReader reader = box.payload();
        // ISO/IED 14496-12:2015 pg.37
        reader.skip(4);
        long entryCount = Math.min(reader.getUInt32(), (box.box().payloadSize - 8) / 8);
        if (media instanceof Mp4SoundDirectory) {
            if (header != null && header.timeScale != null)
                media.setDouble(Mp4SoundDirectory.TAG_AUDIO_SAMPLE_RATE, header.timeScale);
            return;
        }
        if (media instanceof Mp4MetaDirectory) {
            TimedMetadata timed = box.get(TimedMetadata.class);
            if (timed == null)
                return;
            for (long i = 0; i < entryCount; i++) {
                timed.samples += reader.getUInt32();
                reader.skip(4);
            }
            EmptyEdit edit = box.get(EmptyEdit.class);
            if (edit != null)
                timed.emptyEdit = edit.duration;
            return;
        }
        if (!(media instanceof Mp4VideoDirectory) || header == null || header.timeScale == null || header.duration == null)
            return;
        float sampleCount = 0;
        for (long i = 0; i < entryCount; i++) {
            sampleCount += reader.getUInt32();
            reader.skip(4);
        }
        if (sampleCount > 0 && header.duration > 0)
            media.setFloat(Mp4VideoDirectory.TAG_FRAME_RATE, (float) header.timeScale / ((float) header.duration / sampleCount));
    }

    /**
     * A timed metadata track ({@code hdlr} of {@code meta}), as far as the still image time of a
     * Live Photo needs: whether its {@code mebx} entry names the key, how many samples it has, and
     * the empty edit before its media. The time comes from these alone; the sample itself holds
     * only a marker, so {@code mdat} is not read.
     */
    private static final class TimedMetadata
    {
        boolean stillImageTime;
        long samples;
        /** The duration of the empty edit before the media in the movie's time scale, or -1 without one. */
        long emptyEdit = -1;
    }

    /** The timed metadata tracks of the file, for the still image time once {@code moov} is read. */
    private static final class TimedMetadataTracks
    {
        final List<TimedMetadata> all = new ArrayList<TimedMetadata>(1);
    }

    /** The duration of the empty edit a track's edit list starts with, in the movie's time scale. */
    private static final class EmptyEdit
    {
        final long duration;

        EmptyEdit(long duration)
        {
            this.duration = duration;
        }
    }

    /** Registers the track of {@code hdlr} as timed metadata, and the still image time for when {@code moov} is read. */
    private static void timedMetadata(@NotNull IsoBoxContext hdlr)
    {
        IsoBoxContext trak = hdlr.ancestor("trak");
        IsoBoxContext moov = hdlr.ancestor("moov");
        if (trak == null || moov == null)
            return;
        TimedMetadataTracks tracks = moov.get(TimedMetadataTracks.class);
        if (tracks == null) {
            final TimedMetadataTracks created = new TimedMetadataTracks();
            tracks = created;
            moov.put(TimedMetadataTracks.class, created);
            moov.whenDone(new IsoBoxReaders.Callback()
            {
                @Override
                public void read(@NotNull IsoBoxContext done)
                {
                    readStillImageTime(done, created);
                }
            });
        }
        TimedMetadata track = new TimedMetadata();
        tracks.all.add(track);
        trak.put(TimedMetadata.class, track);
    }

    /**
     * The still image time of a Live Photo, once {@code moov} is read: the track keyed
     * {@code still-image-time} holds one sample that an empty edit places at the moment the
     * photo was taken; without an empty edit the photo is the first frame. As Tika does, nothing
     * is read when the track holds more than one sample.
     */
    private static void readStillImageTime(@NotNull IsoBoxContext moov, @NotNull TimedMetadataTracks tracks)
    {
        for (TimedMetadata track : tracks.all) {
            if (!track.stillImageTime || track.samples != 1)
                continue;
            Long movieTimescale = directory(moov).getLongObject(Mp4Directory.TAG_TIME_SCALE);
            Rational time = track.emptyEdit <= 0 ? new Rational(0, 1)
                : movieTimescale != null && movieTimescale > 0 ? new Rational(track.emptyEdit, movieTimescale) : null;
            if (time == null)
                return;
            LivePhotoDirectory directory = new LivePhotoDirectory();
            directory.setRational(LivePhotoDirectory.TAG_STILL_IMAGE_TIME, time);
            moov.metadata().addDirectory(directory);
            moov.root().put(LivePhotoDirectory.class, directory);
            return;
        }
    }

    /** {@code trak/edts/elst}: the empty edit before the media, if the list starts with one. */
    private static void readEditList(@NotNull IsoBoxContext box) throws IOException
    {
        IsoBoxContext trak = box.ancestor("trak");
        if (trak == null)
            return;
        SequentialReader payload = box.payload();
        int version = payload.getUInt8();
        payload.skip(3);
        if (payload.getUInt32() == 0)
            return;
        long duration = version == 1 ? payload.getInt64() : payload.getUInt32();
        long mediaTime = version == 1 ? payload.getInt64() : payload.getInt32();
        if (mediaTime == -1)
            trak.put(EmptyEdit.class, new EmptyEdit(duration));
    }

    /** The first sample entry of {@code stsd} after its size and type, if it is a {@code mebx} entry; else empty. */
    @NotNull
    private static byte[] sampleEntry(@NotNull IsoBoxContext stsd) throws IOException
    {
        SequentialReader payload = stsd.payload();
        payload.skip(4);
        if (payload.getUInt32() == 0)
            return new byte[0];
        long size = payload.getUInt32();
        String format = payload.getString(4);
        long remaining = stsd.box().payloadSize - payload.getPosition();
        if (!format.equals("mebx") || size < 8 || size - 8 > remaining)
            return new byte[0];
        return payload.getBytes((int) (size - 8));
    }

    /**
     * Canon's {@code CNTH} holds a {@code CNDA} atom with an Exif JPEG thumbnail; its date and
     * time go to {@link Mp4Directory#TAG_CANON_THUMBNAIL_DATE_TIME}.
     */
    private static void readCanonThumbnail(@NotNull IsoBoxContext box) throws IOException
    {
        SequentialReader payload = box.payload();
        long size = payload.getUInt32();
        if (size == 0) // Canon ends the atom before with a 32-bit zero
            size = payload.getUInt32();
        String type = payload.getString(4);
        int header = 8;
        if (size == 1) {
            size = payload.getInt64();
            header = 16;
        }
        long remaining = box.box().payloadSize - payload.getPosition();
        if (!type.equals("CNDA") || size < header)
            return;
        byte[] jpeg = payload.getBytes((int) Math.min(size - header, remaining));
        Metadata thumbnail;
        try {
            thumbnail = JpegMetadataReader.readMetadata(new ByteArrayInputStream(jpeg), Collections.singletonList(new ExifReader()));
        } catch (JpegProcessingException e) {
            return;
        }
        ExifIFD0Directory exif = thumbnail.getFirstDirectoryOfType(ExifIFD0Directory.class);
        String dateTime = exif == null ? null : exif.getDescription(ExifDirectoryBase.TAG_DATETIME);
        if (dateTime != null)
            directory(box).setString(Mp4Directory.TAG_CANON_THUMBNAIL_DATE_TIME, dateTime);
    }

    /** The directory a {@code meta}'s {@code hdlr} chose for the rest of it, and the keys of its values. */
    private static final class Meta
    {
        @NotNull
        final String handler;
        @NotNull
        final QuickTimeMetadataDirectory directory;
        final List<String> keys = new ArrayList<String>();

        Meta(@NotNull String handler, @NotNull QuickTimeMetadataDirectory directory)
        {
            this.handler = handler;
            this.directory = directory;
        }
    }

    /** {@code meta/hdlr}: {@code mdta} values are named by {@code keys}, {@code mdir} values by their list atom's type. */
    private static void readMetadataHandler(@NotNull IsoBoxContext box) throws IOException
    {
        SequentialReader payload = box.payload();
        payload.skip(8);
        String componentSubtype = payload.getString(4);
        if (!componentSubtype.equals(HANDLER_METADATA_DIRECTORY) && !componentSubtype.equals(HANDLER_METADATA_DATA))
            return;
        QuickTimeMetadataDirectory directory = new QuickTimeMetadataDirectory();
        box.metadata().addDirectory(directory);
        box.parent().put(Meta.class, new Meta(componentSubtype, directory));
    }

    private static void readKeys(@NotNull IsoBoxContext box) throws IOException
    {
        Meta meta = box.get(Meta.class);
        if (meta == null || !meta.handler.equals(HANDLER_METADATA_DATA))
            return;
        SequentialReader reader = box.payload();
        // Version 1-byte and Flags 3-bytes
        reader.skip(4);
        int entryCount = reader.getInt32();
        for (int i = 0; i < entryCount; i++) {
            int keySize = reader.getInt32();
            if (keySize < 8) {
                meta.directory.addError("Key size too small: " + keySize);
                break;
            }
            reader.skip(4); // key namespace
            meta.keys.add(reader.getString(keySize - 8, "UTF-8"));
        }
    }

    /**
     * A big-endian integer of {@code length} bytes, up to 8: an {@code int} when it fits one as
     * the {@code mdta} integer types were read before, else a {@code long}.
     */
    private static void setInteger(@NotNull QuickTimeMetadataDirectory directory, int tag, @NotNull SequentialReader reader,
                                   int length, boolean signed) throws IOException
    {
        if (length < 1 || length > 8)
            return;
        long value = readInteger(reader, length, signed);
        if (length <= 4 && (signed || length < 4))
            directory.setInt(tag, (int) value);
        else
            directory.setLong(tag, value);
    }

    private static long readInteger(@NotNull SequentialReader reader, int length, boolean signed) throws IOException
    {
        long value = signed ? reader.getInt8() : reader.getUInt8();
        for (int i = 1; i < length; i++)
            value = (value << 8) | reader.getUInt8();
        return value;
    }

    /** The value of a key without a tag, as text, for {@link QuickTimeMetadataDirectory#TAG_OTHER_KEYS}. */
    private static void readOtherKey(@NotNull QuickTimeMetadataDirectory directory, @NotNull String key,
                                     @NotNull SequentialReader reader, int type, int length) throws IOException
    {
        String value;
        switch (type) {
            case 1:
                value = reader.getString(length, "UTF-8");
                break;
            case 21: case 65: case 66: case 67: case 74:
                if (length < 1 || length > 8)
                    return;
                value = Long.toString(readInteger(reader, length, true));
                break;
            case 22: case 75: case 76: case 77: case 78:
                if (length < 1 || length > 8)
                    return;
                value = Long.toString(readInteger(reader, length, false));
                break;
            case 23:
                if (length != 4)
                    return;
                value = Float.toString(reader.getFloat32());
                break;
            case 24:
                if (length != 8)
                    return;
                value = Double.toString(reader.getDouble64());
                break;
            default:
                return;
        }
        directory.addOtherKey(key, value);
    }

    private static void readData(@NotNull IsoBoxContext box) throws IOException
    {
        Meta meta = box.get(Meta.class);
        if (meta == null)
            return;
        IsoBoxContext entry = box.parent();
        String key;
        if (meta.handler.equals(HANDLER_METADATA_DATA)) {
            // the entry's type is the 1-based index into 'keys', as a 32-bit integer
            int index = entry.box().typeCode;
            if (index < 1 || index > meta.keys.size())
                return;
            key = meta.keys.get(index - 1);
        } else {
            key = entry.box().type;
        }
        Integer tag = QuickTimeMetadataDirectory.getTagType(key);
        long payloadSize = box.box().payloadSize;
        if (payloadSize < 8)
            return;
        SequentialReader reader = box.payload();
        int length = (int) (payloadSize - 8);
        // type indicator: a type set byte and a 24-bit well-known type; then 4 bytes locale indicator
        int type = reader.getInt32() & 0xFFFFFF;
        reader.skip(4);
        if (tag == null) {
            // a key without a tag of its own is kept by name, as text, if it has a textual or numeric value
            if (meta.handler.equals(HANDLER_METADATA_DATA))
                readOtherKey(meta.directory, key, reader, type, length);
            return;
        }
        if (type == 0) {
            // implicit: iTunes' binary values, else text as the list reader always read it
            if (key.equals("trkn") || key.equals("disk")) {
                if (length < 6)
                    return;
                reader.skip(2);
                meta.directory.setIntArray(tag, new int[] {reader.getUInt16(), reader.getUInt16()});
            } else if (key.equals("gnre")) {
                if (length >= 2)
                    meta.directory.setInt(tag, reader.getUInt16());
            } else if (length <= 4 && IMPLICIT_INTEGER_KEYS.contains(key)) {
                setInteger(meta.directory, tag, reader, length, false);
            } else {
                meta.directory.setString(tag, new String(reader.getBytes(length)));
            }
            return;
        }
        switch (type) {
            case 1:
                String text = reader.getString(length, "UTF-8");
                meta.directory.setString(tag, text);
                if (key.equals(KEY_LOCATION))
                    readLocation(text, directory(box));
                break;
            case 13:
            case 14:
            case 27:
                meta.directory.setByteArray(tag, reader.getBytes(length));
                break;
            case 21: // BE signed integer of 1, 2, 4 or 8 bytes
            case 65: // BE signed 8-bit
            case 66: // BE signed 16-bit
            case 67: // BE signed 32-bit
            case 74: // BE signed 64-bit
                setInteger(meta.directory, tag, reader, length, true);
                break;
            case 22: // BE unsigned integer of 1, 2, 4 or 8 bytes
            case 75: // BE unsigned 8-bit
            case 76: // BE unsigned 16-bit
            case 77: // BE unsigned 32-bit
            case 78: // BE unsigned 64-bit
                setInteger(meta.directory, tag, reader, length, false);
                break;
            case 23:
                meta.directory.setFloat(tag, reader.getFloat32());
                break;
            case 24:
                meta.directory.setDouble(tag, reader.getDouble64());
                break;
            case 30:
                int[] value = new int[length / 4];
                for (int i = 0; i < value.length; i++)
                    value[i] = reader.getInt32();
                meta.directory.setIntArray(tag, value);
                break;
        }
    }

    /** ISO 6709 Annex H: latitude, longitude and an optional altitude, each signed; the string ends with a slash. */
    private static final Pattern COORDINATES = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)?");

    /**
     * Sets the latitude, longitude and altitude of an ISO 6709 string such as
     * {@code +48.8577+002.2950+035.000/}. Latitude and longitude are degrees, degrees and
     * minutes, or degrees, minutes and seconds, told apart by the digits before the point: two
     * (three for longitude) for degrees alone, two more for minutes, four more for seconds.
     */
    static void readLocation(@NotNull String iso6709, @NotNull Mp4Directory directory)
    {
        Matcher matcher = COORDINATES.matcher(iso6709);
        if (!matcher.find())
            return;
        directory.setDouble(Mp4Directory.TAG_LATITUDE, angle(matcher.group(1), 2));
        directory.setDouble(Mp4Directory.TAG_LONGITUDE, angle(matcher.group(2), 3));
        if (matcher.group(3) != null)
            directory.setDouble(Mp4Directory.TAG_ALTITUDE, Double.parseDouble(matcher.group(3)));
    }

    private static double angle(@NotNull String token, int degreeDigits)
    {
        int point = token.indexOf('.');
        String whole = point < 0 ? token.substring(1) : token.substring(1, point);
        String fraction = point < 0 ? "" : token.substring(point);
        double value;
        if (whole.length() == degreeDigits + 2) {
            value = Integer.parseInt(whole.substring(0, degreeDigits))
                + Double.parseDouble(whole.substring(degreeDigits) + fraction) / 60;
        } else if (whole.length() == degreeDigits + 4) {
            value = Integer.parseInt(whole.substring(0, degreeDigits))
                + Integer.parseInt(whole.substring(degreeDigits, degreeDigits + 2)) / 60.0
                + Double.parseDouble(whole.substring(degreeDigits + 2) + fraction) / 3600;
        } else {
            value = Double.parseDouble(whole + fraction);
        }
        return token.charAt(0) == '-' ? -value : value;
    }

    /**
     * The boxes inside a sound or video sample entry that say more about the stream: the
     * bitrates of {@code esds} (MPEG-4 audio; QuickTime nests it in {@code wave}) and
     * {@code btrt}, and for a protected entry the original format in {@code sinf/frma} and the
     * scheme in {@code sinf/schm}.
     */
    private static void readSampleEntryBox(@NotNull IsoBoxContext box) throws IOException
    {
        Mp4MediaDirectory media = box.get(Mp4MediaDirectory.class);
        if (media == null)
            return;
        String type = box.box().type;
        SequentialReader payload = box.payload();
        if (type.equals("btrt")) {
            if (!(media instanceof Mp4VideoDirectory))
                return;
            payload.skip(4); // decoding buffer size
            media.setLong(Mp4VideoDirectory.TAG_MAX_BITRATE, payload.getUInt32());
            media.setLong(Mp4VideoDirectory.TAG_AVERAGE_BITRATE, payload.getUInt32());
        } else if (type.equals("esds")) {
            if (media instanceof Mp4SoundDirectory)
                readDecoderConfiguration(payload, box.box().payloadSize, (Mp4SoundDirectory) media);
        } else if (type.equals("frma")) {
            String original = payload.getString(4);
            IsoBoxContext entry = box.parent().parent();
            if (!PROTECTED_FORMATS.contains(entry.box().type))
                return;
            if (media instanceof Mp4SoundDirectory) {
                Mp4Dictionary.setLookup(Mp4SoundDirectory.TAG_AUDIO_FORMAT, original, media);
                media.setString(Mp4SoundDirectory.TAG_AUDIO_FORMAT_RAW, original);
            } else if (media instanceof Mp4VideoDirectory) {
                Mp4Dictionary.setLookup(Mp4VideoDirectory.TAG_COMPRESSION_TYPE, original, media);
                media.setString(Mp4VideoDirectory.TAG_COMPRESSION_TYPE_RAW, original);
            }
        } else if (type.equals("schm")) {
            payload.skip(4); // version and flags
            media.setString(Mp4MediaDirectory.TAG_PROTECTION_SCHEME, payload.getString(4));
        }
    }

    /**
     * ISO/IEC 14496-1: the ES_Descriptor (tag 3) of {@code esds} holds a DecoderConfigDescriptor
     * (tag 4) with the buffer size and the maximum and average bitrates. Descriptor lengths are
     * 7-bit groups with a continuation bit.
     */
    private static void readDecoderConfiguration(@NotNull SequentialReader payload, long size, @NotNull Mp4SoundDirectory sound) throws IOException
    {
        if (size < 4 + 2 + 3 + 2 + 13)
            return;
        payload.skip(4); // version and flags
        if (payload.getUInt8() != 0x03)
            return;
        descriptorLength(payload);
        payload.skip(2); // ES_ID
        int flags = payload.getUInt8();
        if ((flags & 0x80) != 0)
            payload.skip(2); // dependsOn_ES_ID
        if ((flags & 0x40) != 0)
            payload.skip(payload.getUInt8()); // URL
        if ((flags & 0x20) != 0)
            payload.skip(2); // OCR_ES_Id
        if (payload.getUInt8() != 0x04)
            return;
        descriptorLength(payload);
        payload.skip(5); // object type, stream type and flags, buffer size
        long maxBitrate = payload.getUInt32();
        long averageBitrate = payload.getUInt32();
        if (maxBitrate > 0)
            sound.setLong(Mp4SoundDirectory.TAG_MAX_BITRATE, maxBitrate);
        if (averageBitrate > 0)
            sound.setLong(Mp4SoundDirectory.TAG_AVERAGE_BITRATE, averageBitrate);
    }

    private static int descriptorLength(@NotNull SequentialReader payload) throws IOException
    {
        int length = 0;
        for (int i = 0; i < 4; i++) {
            int b = payload.getUInt8();
            length = (length << 7) | (b & 0x7F);
            if ((b & 0x80) == 0)
                break;
        }
        return length;
    }

    /** A Windows {@code Xtra} box: properties of the Windows file properties dialog, as UTF-16LE strings. */
    private static void readXtra(@NotNull SequentialReader reader, long length, @NotNull Mp4Directory directory) throws IOException
    {
        while (reader.getPosition() < length) {
            long entryStart = reader.getPosition();
            long entrySize = reader.getUInt32();
            if (entrySize < 12)
                break;
            String keyName = reader.getString((int) reader.getUInt32(), "UTF-8");
            long entryCount = reader.getUInt32();
            if (keyName.equals("WM/SubTitle")) {
                directory.setString(Mp4Directory.TAG_SUBTITLE, readXtraValues(reader, entryCount));
            } else if (keyName.equals("WM/SharedUserRating")) {
                reader.skip(6);
                directory.setLong(Mp4Directory.TAG_USER_RATING, reader.getInt64());
            } else if (keyName.equals("WM/Category")) {
                directory.setString(Mp4Directory.TAG_CATEGORY, readXtraValues(reader, entryCount));
            } else if (keyName.equals("WM/Mood")) {
                directory.setString(Mp4Directory.TAG_MOOD, readXtraValues(reader, entryCount));
            }
            long consumed = reader.getPosition() - entryStart;
            if (entrySize > consumed)
                reader.skip(entrySize - consumed);
        }
    }

    private static String readXtraValues(@NotNull SequentialReader reader, long entryCount) throws IOException
    {
        List<String> result = new ArrayList<String>();
        for (long i = 0; i < entryCount; i++) {
            long valueSize = reader.getUInt32();
            reader.getUInt16();
            if (valueSize < 6)
                break;
            result.add(reader.getString((int) valueSize - 6, "UTF-16LE").replace("\0", ""));
        }
        return StringUtil.join(result, " | ");
    }

    private enum UuidType { Unknown, Exif, PhotoshopImageResources, IptcIim, Xmp }

    private static final ByteTrie<UuidType> UUID_TYPES = new ByteTrie<UuidType>();

    static {
        UUID_TYPES.setDefaultValue(UuidType.Unknown);
        UUID_TYPES.addPath(UuidType.Exif, new byte[] { 0x05, 0x37, (byte)0xcd, (byte)0xab, (byte)0x9d, 0x0c, 0x44, 0x31, (byte)0xa7, 0x2a, (byte)0xfa, 0x56, 0x1f, 0x2a, 0x11, 0x3e });
        UUID_TYPES.addPath(UuidType.PhotoshopImageResources, new byte[] { 0x2c, 0x4c, 0x01, 0x00, (byte)0x85, 0x04, 0x40, (byte)0xb9, (byte)0xa0, 0x3e, 0x56, 0x21, 0x48, (byte)0xd6, (byte)0xdf, (byte)0xeb });
        UUID_TYPES.addPath(UuidType.IptcIim, new byte[] { 0x33, (byte)0xc7, (byte)0xa4, (byte)0xd2, (byte)0xb8, 0x1d, 0x47, 0x23, (byte)0xa0, (byte)0xba, (byte)0xf1, (byte)0xa3, (byte)0xe0, (byte)0x97, (byte)0xad, 0x38 });
        UUID_TYPES.addPath(UuidType.Xmp, new byte[] { (byte)0xbe, 0x7a, (byte)0xcf, (byte)0xcb, (byte)0x97, (byte)0xa9, 0x42, (byte)0xe8, (byte)0x9c, 0x71, (byte)0x99, (byte)0x94, (byte)0x91, (byte)0xe3, (byte)0xaf, (byte)0xac });
    }

    /** A {@code uuid} box: Exif, IPTC, Photoshop or XMP data, or the raw user data of any other usertype. */
    private static void readUuid(@NotNull IsoBoxContext box) throws IOException
    {
        Metadata metadata = box.metadata();
        Mp4UuidBoxDirectory directory = new Mp4UuidBoxDirectory();
        metadata.addDirectory(directory);
        byte[] usertype = box.box().usertype;
        byte[] payload = box.payloadBytes();
        switch (UUID_TYPES.find(usertype)) {
            case Exif:
                new ExifReader().extract(new ByteArrayReader(payload), metadata, 0, directory);
                break;
            case IptcIim:
                new IptcReader().extract(new SequentialByteArrayReader(payload), metadata, payload.length, directory);
                break;
            case PhotoshopImageResources:
                new PhotoshopReader().extract(new SequentialByteArrayReader(payload), payload.length, metadata, directory);
                break;
            case Xmp:
                new XmpReader().extract(payload, 0, payload.length, metadata, directory);
                break;
            default:
                ByteBuffer bytes = ByteBuffer.wrap(usertype);
                directory.setString(Mp4UuidBoxDirectory.TAG_UUID, new UUID(bytes.getLong(), bytes.getLong()).toString());
                directory.setByteArray(Mp4UuidBoxDirectory.TAG_USER_DATA, payload);
                break;
        }
    }

    /** A track's {@code mdhd}: dates, language, time scale and duration. */
    private static final class MediaHeader
    {
        long creationTime;
        long modificationTime;
        Long timeScale;
        Long duration;
        String language;

        static MediaHeader read(@NotNull SequentialReader reader) throws IOException
        {
            // ISO/IED 14496-12:2015 pg.29
            MediaHeader header = new MediaHeader();
            int version = reader.getUInt8();
            reader.skip(3);
            header.creationTime = version == 1 ? reader.getInt64() : reader.getUInt32();
            header.modificationTime = version == 1 ? reader.getInt64() : reader.getUInt32();
            header.timeScale = version == 1 ? (long) reader.getInt32() : reader.getUInt32();
            header.duration = version == 1 ? reader.getInt64() : reader.getUInt32();
            header.language = IsoLanguageCode.decode(reader.getUInt16() & 0x7FFF);
            return header;
        }

        void apply(@NotNull Mp4MediaDirectory directory)
        {
            directory.setDate(Mp4MediaDirectory.TAG_CREATION_TIME, DateUtil.get1Jan1904EpochDate(creationTime));
            directory.setDate(Mp4MediaDirectory.TAG_MODIFICATION_TIME, DateUtil.get1Jan1904EpochDate(modificationTime));
            if (language != null)
                directory.setString(Mp4MediaDirectory.TAG_LANGUAGE_CODE, language);
        }
    }
}
