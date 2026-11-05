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
import com.drew.imaging.isobmff.IsoBoxIterator;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.Metadata;
import com.drew.metadata.file.FileSystemMetadataReader;
import com.drew.metadata.mp4.Mp4Directory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Obtains metadata from MP4 files, with box readers registered by path; see
 * {@link IsoBoxReaders} for how they are walked.
 */
public class Mp4MetadataReader
{
    /**
     * The readers {@link #readMetadata(InputStream)} uses. Pass them, with readers of your own,
     * to {@link #readMetadata(InputStream, Iterable)} to read further boxes.
     */
    public static final List<IsoBoxReader> ALL_READERS = Mp4Readers.ALL_READERS;

    /** The reader in {@link #ALL_READERS} for {@code udta} boxes, for callers that read them their own way. */
    public static final IsoBoxReader USER_DATA_READER = Mp4Readers.USER_DATA_READER;

    private static final IsoBoxReaders DEFAULT_READERS = compile(ALL_READERS);

    /** The largest payload read into memory to share between several readers registered for the same box. */
    private static final long MAX_SHARED_PAYLOAD = 64L * 1024 * 1024;

    /** Prepares a walk over the MP4 structure that calls {@code readers}; build it once and reuse it. */
    @NotNull
    public static IsoBoxReaders compile(@NotNull Iterable<? extends IsoBoxReader> readers)
    {
        return new IsoBoxReaders(Mp4Readers.LAYOUT, readers);
    }

    /** The handler type of the track a box belongs to, such as {@code soun} or {@code vide}, for readers of track boxes. */
    @Nullable
    public static String handlerType(@NotNull IsoBoxContext box)
    {
        return Mp4Readers.handlerType(box);
    }

    @NotNull
    public static Metadata readMetadata(@NotNull final File file) throws IOException
    {
        InputStream inputStream = new FileInputStream(file);
        Metadata metadata;
        try {
            metadata = readMetadata(inputStream);
        } finally {
            inputStream.close();
        }
        new FileSystemMetadataReader().read(file, metadata);
        return metadata;
    }

    @NotNull
    public static Metadata readMetadata(@NotNull InputStream inputStream)
    {
        Metadata metadata = new Metadata();
        extract(inputStream, DEFAULT_READERS, newRoot(metadata, null));
        return metadata;
    }

    @NotNull
    public static Metadata readMetadata(@NotNull InputStream inputStream, @NotNull Iterable<? extends IsoBoxReader> readers)
    {
        Metadata metadata = new Metadata();
        extract(inputStream, compile(readers), newRoot(metadata, null));
        return metadata;
    }

    /**
     * The file context for reading into {@code metadata}: it adds an {@link Mp4Directory} and
     * stores it under its class. Problems go to {@code errors}, or to the {@link Mp4Directory}
     * when it is {@code null}. Store state for your own readers in it before calling
     * {@link #extract(InputStream, IsoBoxReaders, IsoBoxContext)}.
     */
    @NotNull
    public static IsoBoxContext newRoot(@NotNull Metadata metadata, @Nullable IsoBoxContext.ErrorHandler errors)
    {
        Mp4Directory directory = new Mp4Directory();
        metadata.addDirectory(directory);
        IsoBoxContext root = errors == null ? IsoBoxContext.root(metadata, directory) : IsoBoxContext.root(metadata, errors);
        root.put(Mp4Directory.class, directory);
        return root;
    }

    /** Reads {@code inputStream} with {@code readers} (see {@link #compile}), starting from {@code root} (see {@link #newRoot}). */
    public static void extract(@NotNull InputStream inputStream, @NotNull IsoBoxReaders readers, @NotNull IsoBoxContext root)
    {
        readers.read(new IsoBoxIterator(inputStream), root, MAX_SHARED_PAYLOAD);
    }
}
