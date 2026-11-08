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

import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxLayout;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.isobmff.QuickTimeLayout;
import com.drew.lang.DateUtil;
import com.drew.lang.SequentialByteArrayReader;
import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.mov.QuickTimeContext;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.atoms.Atom;
import com.drew.metadata.mov.atoms.FileTypeCompatibilityAtom;
import com.drew.metadata.mov.atoms.HandlerReferenceAtom;
import com.drew.metadata.mov.atoms.MediaHeaderAtom;
import com.drew.metadata.mov.atoms.MovieHeaderAtom;
import com.drew.metadata.mov.atoms.MusicSampleDescriptionAtom;
import com.drew.metadata.mov.atoms.SoundInformationMediaHeaderAtom;
import com.drew.metadata.mov.atoms.SoundSampleDescriptionAtom;
import com.drew.metadata.mov.atoms.SubtitleSampleDescriptionAtom;
import com.drew.metadata.mov.atoms.TextSampleDescriptionAtom;
import com.drew.metadata.mov.atoms.TimeToSampleAtom;
import com.drew.metadata.mov.atoms.TimecodeInformationMediaAtom;
import com.drew.metadata.mov.atoms.TimecodeSampleDescriptionAtom;
import com.drew.metadata.mov.atoms.TrackHeaderAtom;
import com.drew.metadata.mov.atoms.VideoInformationMediaHeaderAtom;
import com.drew.metadata.mov.atoms.VideoSampleDescriptionAtom;
import com.drew.metadata.mov.atoms.canon.CanonThumbnailAtom;
import com.drew.metadata.mov.media.QuickTimeMediaDirectory;
import com.drew.metadata.mov.media.QuickTimeMusicDirectory;
import com.drew.metadata.mov.media.QuickTimeSoundDirectory;
import com.drew.metadata.mov.media.QuickTimeSubtitleDirectory;
import com.drew.metadata.mov.media.QuickTimeTextDirectory;
import com.drew.metadata.mov.media.QuickTimeTimecodeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory;
import com.drew.metadata.xmp.XmpReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The readers of QuickTime files, on {@link QuickTimeLayout}. They fill {@link QuickTimeDirectory},
 * one media directory per track whose {@code hdlr} names a known component type, and one
 * {@link QuickTimeMetadataDirectory} per {@code meta} whose {@code hdlr} is {@code mdta} (keys and
 * values) or {@code mdir} (an iTunes-style list).
 * <p>
 * The atom classes of {@code com.drew.metadata.mov.atoms} read the payloads as before. A
 * {@link QuickTimeContext} on the file context holds the latest {@code mdhd}'s times for the media
 * directories and sample tables, as the handler-based reader did.
 */
final class QuickTimeReaders
{
    private QuickTimeReaders() {}

    static final IsoBoxLayout LAYOUT = QuickTimeLayout.INSTANCE;

    private static final String HANDLER_METADATA_DIRECTORY = "mdir";
    private static final String HANDLER_METADATA_DATA = "mdta";

    private static final String MEDIA = "moov/trak/mdia/";
    private static final String MEDIA_INFO = MEDIA + "minf/";
    private static final String SAMPLE_TABLE = MEDIA_INFO + "stbl/";
    /** Where the handler-based reader accepted file-level atoms: at the top and in the containers it entered. */
    private static final String[] GENERAL = {"", "moov/", "moov/udta/", "moov/trak/", "moov/trak/udta/", "moov/trak/mdia/", "moov/meta/", "moov/udta/meta/", "moov/trak/meta/"};
    /** Where a {@code meta} with keys or an iTunes-style list can be. */
    private static final String[] META = {"meta/", "moov/meta/", "moov/udta/meta/", "moov/trak/meta/", "moov/trak/udta/meta/"};

    /** The directory a track's {@code hdlr} chose for the rest of its {@code mdia}. */
    private static final class Media
    {
        @NotNull
        final QuickTimeDirectory directory;

        Media(@NotNull QuickTimeDirectory directory)
        {
            this.directory = directory;
        }
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

    static final List<IsoBoxReader> ALL_READERS = Collections.unmodifiableList(Arrays.asList(
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                new FileTypeCompatibilityAtom(box.payload(), new Atom(box.box())).addMetadata(directory(box));
            }
        }, "ftyp"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                new MovieHeaderAtom(box.payload(), new Atom(box.box())).addMetadata(directory(box));
            }
        }, "moov/mvhd"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                new TrackHeaderAtom(box.payload(), new Atom(box.box())).addMetadata(directory(box));
            }
        }, "moov/trak/tkhd"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                new MediaHeaderAtom(box.payload(), new Atom(box.box()), context(box));
            }
        }, MEDIA + "mdhd"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readMediaHandler(box);
            }
        }, MEDIA + "hdlr"),
        on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                readMediaInformation(box);
            }
        }, MEDIA_INFO + "vmhd", MEDIA_INFO + "smhd", MEDIA_INFO + "gmhd/tmcd/tcmi", MEDIA_INFO + "gmhd/tcmi"),
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
                new CanonThumbnailAtom(new SequentialByteArrayReader(box.payloadBytes())).addMetadata(directory(box));
            }
        }, paths(GENERAL, "CNTH")),
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
        }, paths(META, "ilst/*/data"))));

    private static IsoBoxReader on(IsoBoxReaders.Callback callback, String... paths)
    {
        return IsoBoxReaders.on(callback, paths);
    }

    private static String[] paths(String[] prefixes, String type)
    {
        String[] paths = new String[prefixes.length];
        for (int i = 0; i < prefixes.length; i++)
            paths[i] = prefixes[i] + type;
        return paths;
    }

    @NotNull
    private static QuickTimeDirectory directory(@NotNull IsoBoxContext box)
    {
        return box.root().get(QuickTimeDirectory.class);
    }

    @NotNull
    private static QuickTimeContext context(@NotNull IsoBoxContext box)
    {
        return box.root().get(QuickTimeContext.class);
    }

    /** {@code mdia/hdlr}: the component type chooses the media directory, dated from the latest {@code mdhd}. */
    private static void readMediaHandler(@NotNull IsoBoxContext box) throws IOException
    {
        String componentType = new HandlerReferenceAtom(box.payload(), new Atom(box.box())).getComponentType();
        QuickTimeDirectory directory = mediaDirectory(componentType);
        if (directory == null)
            return;
        QuickTimeContext context = context(box);
        if (context.creationTime != null && context.modificationTime != null) {
            directory.setDate(QuickTimeMediaDirectory.TAG_CREATION_TIME, DateUtil.get1Jan1904EpochDate(context.creationTime));
            directory.setDate(QuickTimeMediaDirectory.TAG_MODIFICATION_TIME, DateUtil.get1Jan1904EpochDate(context.modificationTime));
        }
        box.metadata().addDirectory(directory);
        box.parent().put(Media.class, new Media(directory));
    }

    @Nullable
    private static QuickTimeDirectory mediaDirectory(@NotNull String componentType)
    {
        if (componentType.equals("soun"))
            return new QuickTimeSoundDirectory();
        if (componentType.equals("vide"))
            return new QuickTimeVideoDirectory();
        if (componentType.equals("tmcd"))
            return new QuickTimeTimecodeDirectory();
        if (componentType.equals("text"))
            return new QuickTimeTextDirectory();
        if (componentType.equals("sbtl"))
            return new QuickTimeSubtitleDirectory();
        if (componentType.equals("musi"))
            return new QuickTimeMusicDirectory();
        return null;
    }

    private static void readMediaInformation(@NotNull IsoBoxContext box) throws IOException
    {
        Media media = box.get(Media.class);
        if (media == null)
            return;
        String type = box.box().type;
        SequentialReader reader = box.payload();
        Atom atom = new Atom(box.box());
        if (media.directory instanceof QuickTimeVideoDirectory && type.equals("vmhd"))
            new VideoInformationMediaHeaderAtom(reader, atom).addMetadata((QuickTimeVideoDirectory) media.directory);
        else if (media.directory instanceof QuickTimeSoundDirectory && type.equals("smhd"))
            new SoundInformationMediaHeaderAtom(reader, atom).addMetadata((QuickTimeSoundDirectory) media.directory);
        else if (media.directory instanceof QuickTimeTimecodeDirectory && type.equals("tcmi"))
            new TimecodeInformationMediaAtom(reader, atom).addMetadata((QuickTimeTimecodeDirectory) media.directory);
    }

    private static void readSampleDescription(@NotNull IsoBoxContext box) throws IOException
    {
        Media media = box.get(Media.class);
        if (media == null)
            return;
        SequentialReader reader = box.payload();
        Atom atom = new Atom(box.box());
        QuickTimeDirectory directory = media.directory;
        if (directory instanceof QuickTimeVideoDirectory)
            new VideoSampleDescriptionAtom(reader, atom).addMetadata((QuickTimeVideoDirectory) directory);
        else if (directory instanceof QuickTimeSoundDirectory)
            new SoundSampleDescriptionAtom(reader, atom).addMetadata((QuickTimeSoundDirectory) directory);
        else if (directory instanceof QuickTimeTimecodeDirectory)
            new TimecodeSampleDescriptionAtom(reader, atom).addMetadata((QuickTimeTimecodeDirectory) directory);
        else if (directory instanceof QuickTimeTextDirectory)
            new TextSampleDescriptionAtom(reader, atom).addMetadata((QuickTimeTextDirectory) directory);
        else if (directory instanceof QuickTimeSubtitleDirectory)
            new SubtitleSampleDescriptionAtom(reader, atom).addMetadata((QuickTimeSubtitleDirectory) directory);
        else if (directory instanceof QuickTimeMusicDirectory)
            new MusicSampleDescriptionAtom(reader, atom).addMetadata((QuickTimeMusicDirectory) directory);
    }

    private static void readTimeToSample(@NotNull IsoBoxContext box) throws IOException
    {
        Media media = box.get(Media.class);
        if (media == null)
            return;
        QuickTimeContext context = context(box);
        if (media.directory instanceof QuickTimeVideoDirectory) {
            new TimeToSampleAtom(box.payload(), new Atom(box.box())).addMetadata((QuickTimeVideoDirectory) media.directory, context);
        } else if (media.directory instanceof QuickTimeSoundDirectory) {
            if (context.timeScale != null)
                media.directory.setDouble(QuickTimeSoundDirectory.TAG_AUDIO_SAMPLE_RATE, context.timeScale);
        }
    }

    /** {@code meta/hdlr}: {@code mdta} values are named by {@code keys}, {@code mdir} values by their list atom's type. */
    private static void readMetadataHandler(@NotNull IsoBoxContext box) throws IOException
    {
        String componentType = new HandlerReferenceAtom(box.payload(), new Atom(box.box())).getComponentType();
        if (!componentType.equals(HANDLER_METADATA_DIRECTORY) && !componentType.equals(HANDLER_METADATA_DATA))
            return;
        QuickTimeMetadataDirectory directory = new QuickTimeMetadataDirectory();
        box.metadata().addDirectory(directory);
        box.parent().put(Meta.class, new Meta(componentType, directory));
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
        if (tag == null || payloadSize < 8)
            return;
        SequentialReader reader = box.payload();
        int length = (int) (payloadSize - 8);
        if (meta.handler.equals(HANDLER_METADATA_DIRECTORY)) {
            // 4 bytes: type indicator, 4 bytes: locale indicator
            reader.skip(8);
            meta.directory.setString(tag, new String(reader.getBytes(length)));
            return;
        }
        int type = reader.getInt32();
        // 4 bytes: locale indicator
        reader.skip(4);
        switch (type) {
            case 1:
                meta.directory.setString(tag, reader.getString(length, "UTF-8"));
                break;
            case 13:
            case 14:
            case 27:
                meta.directory.setByteArray(tag, reader.getBytes(length));
                break;
            case 22:
                byte[] buf = new byte[4];
                reader.getBytes(buf, 4 - length, length);
                meta.directory.setInt(tag, new SequentialByteArrayReader(buf).getInt32());
                break;
            case 23:
                meta.directory.setFloat(tag, reader.getFloat32());
                break;
            case 30:
                int[] value = new int[length / 4];
                for (int i = 0; i < value.length; i++)
                    value[i] = reader.getInt32();
                meta.directory.setIntArray(tag, value);
                break;
        }
    }
}
