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
import com.drew.imaging.isobmff.IsoStandardLayout;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
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
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.iptc.IptcReader;
import com.drew.metadata.mp4.Mp4Dictionary;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4HintDirectory;
import com.drew.metadata.mp4.media.Mp4MediaDirectory;
import com.drew.metadata.mp4.media.Mp4MetaDirectory;
import com.drew.metadata.mp4.media.Mp4SoundDirectory;
import com.drew.metadata.mp4.media.Mp4TextDirectory;
import com.drew.metadata.mp4.media.Mp4UuidBoxDirectory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import com.drew.metadata.photoshop.PhotoshopReader;
import com.drew.metadata.xmp.XmpReader;

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
 * The box layout and readers of MP4 files. The layout is the standard one with QuickTime's
 * deviations; the readers fill
 * {@link Mp4Directory}, one {@link Mp4MediaDirectory} per track, and one
 * {@link Mp4UuidBoxDirectory} per {@code uuid} box.
 */
final class Mp4Readers
{
    private static final String TRACK = "moov/trak/";
    private static final String MEDIA = TRACK + "mdia/";
    private static final String MEDIA_INFO = MEDIA + "minf/";
    private static final String SAMPLE_TABLE = MEDIA_INFO + "stbl/";
    private static final String[] USER_DATA = {"udta/", "moov/udta/", TRACK + "udta/"};

    private Mp4Readers() {}

    /**
     * The standard layout, with QuickTime's {@code meta} in {@code moov} and {@code trak}, which
     * has no FullBox header, and its {@code keys}, which has one plus an entry count.
     */
    static final class Layout extends IsoStandardLayout
    {
        @Override
        public boolean enterContainer(@NotNull IsoBoxContext box) throws IOException
        {
            String type = box.box().type;
            IsoBoxContext parent = box.parent();
            String parentType = parent == null || parent.parent() == null ? "" : parent.box().type;
            if (type.equals("meta") && (parentType.equals("moov") || parentType.equals("trak")))
                return true;
            if (type.equals("keys") && parentType.equals("meta")) {
                box.payload().skip(8);
                return true;
            }
            return super.enterContainer(box);
        }
    }

    static final Layout LAYOUT = new Layout();

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
                Matcher matcher = COORDINATES.matcher(payload.getString(length, "UTF-8"));
                if (matcher.find()) {
                    directory.setDouble(Mp4Directory.TAG_LATITUDE, Double.parseDouble(matcher.group(1)));
                    directory.setDouble(Mp4Directory.TAG_LONGITUDE, Double.parseDouble(matcher.group(2)));
                }
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
                Mp4MediaDirectory media = mediaDirectory(IsoStandardLayout.handlerType(box));
                if (media == null)
                    return;
                MediaHeader header = box.get(MediaHeader.class);
                if (header != null)
                    header.apply(media);
                box.metadata().addDirectory(media);
                box.parent().put(Mp4MediaDirectory.class, media);
            }
        }, MEDIA + "hdlr"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readMediaInformation(box);
            }
        }, MEDIA_INFO + "vmhd", MEDIA_INFO + "smhd", MEDIA_INFO + "hmhd"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readSampleDescription(box.payload(), box.get(Mp4MediaDirectory.class));
            }
        }, SAMPLE_TABLE + "stsd"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readTimeToSample(box.payload(), box.get(Mp4MediaDirectory.class), box.get(MediaHeader.class));
            }
        }, SAMPLE_TABLE + "stts"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readUuid(box);
            }
        }, "uuid", "moov/uuid", TRACK + "uuid"),
        USER_DATA_READER));

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
        reader.skip(24);
        long nextTrackID = reader.getUInt32();

        directory.setDate(Mp4Directory.TAG_CREATION_TIME, DateUtil.get1Jan1904EpochDate(creationTime));
        directory.setDate(Mp4Directory.TAG_MODIFICATION_TIME, DateUtil.get1Jan1904EpochDate(modificationTime));
        directory.setLong(Mp4Directory.TAG_DURATION, duration);
        directory.setLong(Mp4Directory.TAG_TIME_SCALE, timescale);
        directory.setRational(Mp4Directory.TAG_DURATION_SECONDS, new Rational(duration, timescale));
        directory.setIntArray(Mp4Directory.TAG_TRANSFORMATION_MATRIX, matrix);
        directory.setDouble(Mp4Directory.TAG_PREFERRED_RATE, ((rate & 0xFFFF0000) >> 16) + (rate & 0x0000FFFF) / Math.pow(2, 4));
        directory.setDouble(Mp4Directory.TAG_PREFERRED_VOLUME, ((volume & 0xFF00) >> 8) + (volume & 0x00FF) / Math.pow(2, 2));
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
        return null;
    }

    private static void readMediaInformation(@NotNull IsoBoxContext box) throws IOException
    {
        Mp4MediaDirectory media = box.get(Mp4MediaDirectory.class);
        SequentialReader reader = box.payload();
        String type = box.box().type;
        if (media instanceof Mp4VideoDirectory && type.equals("vmhd")) {
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

    /** Reads the first entry of a sound or video track's {@code stsd}. */
    private static void readSampleDescription(@NotNull SequentialReader reader, @Nullable Mp4MediaDirectory media) throws IOException
    {
        if (!(media instanceof Mp4SoundDirectory) && !(media instanceof Mp4VideoDirectory))
            return;
        // ISO/IED 14496-12:2015 pg.33: version and flags, entry count, entry size, format
        reader.skip(12);
        String format = reader.getString(4);
        reader.skip(8);
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
        // ISO/IED 14496-12:2015 pg.156
        reader.skip(16);
        int width = reader.getUInt16();
        int height = reader.getUInt16();
        long horizontalResolution = reader.getUInt32();
        long verticalResolution = reader.getUInt32();
        reader.skip(6);
        String compressorName = reader.getString(32).trim();
        int depth = reader.getUInt16();
        Mp4Dictionary.setLookup(Mp4VideoDirectory.TAG_COMPRESSION_TYPE, format, media);
        media.setString(Mp4VideoDirectory.TAG_COMPRESSION_TYPE_RAW, format);
        media.setInt(Mp4VideoDirectory.TAG_WIDTH, width);
        media.setInt(Mp4VideoDirectory.TAG_HEIGHT, height);
        if (!compressorName.isEmpty())
            media.setString(Mp4VideoDirectory.TAG_COMPRESSOR_NAME, compressorName);
        media.setInt(Mp4VideoDirectory.TAG_DEPTH, depth);
        media.setDouble(Mp4VideoDirectory.TAG_HORIZONTAL_RESOLUTION, fixedPoint(horizontalResolution));
        media.setDouble(Mp4VideoDirectory.TAG_VERTICAL_RESOLUTION, fixedPoint(verticalResolution));
    }

    private static double fixedPoint(long value)
    {
        return ((value & 0xFFFF0000) >> 16) + (value & 0xFFFF) / Math.pow(2, 4);
    }

    /** The sample rate of a sound track and the frame rate of a video track. */
    private static void readTimeToSample(@NotNull SequentialReader reader, @Nullable Mp4MediaDirectory media,
                                         @Nullable MediaHeader header) throws IOException
    {
        if (!(media instanceof Mp4SoundDirectory) && !(media instanceof Mp4VideoDirectory))
            return;
        // ISO/IED 14496-12:2015 pg.37
        reader.skip(4);
        long entryCount = reader.getUInt32();
        if (media instanceof Mp4SoundDirectory) {
            reader.skip(entryCount * 8);
            if (header != null && header.timeScale != null)
                media.setDouble(Mp4SoundDirectory.TAG_AUDIO_SAMPLE_RATE, header.timeScale);
            return;
        }
        float sampleCount = 0;
        for (int i = 0; i < entryCount; i++) {
            sampleCount += reader.getUInt32();
            reader.skip(4);
        }
        if (header != null && header.timeScale != null && header.duration != null)
            media.setFloat(Mp4VideoDirectory.TAG_FRAME_RATE, (float) header.timeScale / ((float) header.duration / sampleCount));
    }

    private static final Pattern COORDINATES = Pattern.compile("([+-]\\d+\\.\\d+)([+-]\\d+\\.\\d+)");

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
            int bits = reader.getInt16();
            header.language = new String(new char[] {
                (char) (((bits & 0x7C00) >> 10) + 0x60), (char) (((bits & 0x03E0) >> 5) + 0x60), (char) ((bits & 0x001F) + 0x60)});
            return header;
        }

        void apply(@NotNull Mp4MediaDirectory directory)
        {
            directory.setDate(Mp4MediaDirectory.TAG_CREATION_TIME, DateUtil.get1Jan1904EpochDate(creationTime));
            directory.setDate(Mp4MediaDirectory.TAG_MODIFICATION_TIME, DateUtil.get1Jan1904EpochDate(modificationTime));
            directory.setString(Mp4MediaDirectory.TAG_LANGUAGE_CODE, language);
        }
    }
}
