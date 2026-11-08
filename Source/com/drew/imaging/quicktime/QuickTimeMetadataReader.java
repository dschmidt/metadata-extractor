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

import com.drew.imaging.ImageProcessingException;
import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.isobmff.IsoInput;
import com.drew.imaging.isobmff.QuickTimeLayout;
import com.drew.imaging.mp4.Mp4MetadataReader;
import com.drew.lang.annotations.NotNull;
import com.drew.metadata.Metadata;
import com.drew.metadata.file.FileSystemMetadataReader;
import com.drew.metadata.mov.QuickTimeDirectory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Obtains metadata from QuickTime files into the directories of {@code com.drew.metadata.mov}.
 * The MP4 readers do the reading; what they read is copied into the old directories, which
 * carry the same tags under other numbers.
 *
 * @deprecated {@link Mp4MetadataReader} reads QuickTime files as well, into the directories of
 * {@code com.drew.metadata.mp4}, and is where new tags are added; the QuickTime directories
 * keep the tags they have. {@link com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory},
 * which both fill, stays.
 * @author Payton Garland
 */
@Deprecated
public class QuickTimeMetadataReader
{
    /**
     * The readers {@link #readMetadata(IsoInput)} uses: those of {@link Mp4MetadataReader}, and
     * one that computes the video frame rate as this reader always did. Pass them, with readers
     * of your own, to {@link #compile} to read further boxes; your readers see the MP4
     * directories, which are copied into the QuickTime ones once the file is read.
     */
    public static final List<IsoBoxReader> ALL_READERS;

    static
    {
        List<IsoBoxReader> readers = new ArrayList<IsoBoxReader>(Mp4MetadataReader.ALL_READERS);
        readers.add(QuickTimeDirectories.FIRST_ENTRY_FRAME_RATE);
        ALL_READERS = Collections.unmodifiableList(readers);
    }

    private static final IsoBoxReaders DEFAULT_READERS = compile(ALL_READERS);

    /** Prepares a walk over the QuickTime structure that calls {@code readers}; build it once and reuse it. */
    @NotNull
    public static IsoBoxReaders compile(@NotNull Iterable<? extends IsoBoxReader> readers)
    {
        return new IsoBoxReaders(QuickTimeLayout.INSTANCE, readers);
    }

    /** As {@link #compile(Iterable)}, with the most bytes of a box's payload read into memory for a reader. */
    @NotNull
    public static IsoBoxReaders compile(@NotNull Iterable<? extends IsoBoxReader> readers, long maxBufferedPayload)
    {
        return new IsoBoxReaders(QuickTimeLayout.INSTANCE, readers, maxBufferedPayload);
    }

    /** Reads {@code input}; see {@link IsoInput} for the kinds of input. */
    @NotNull
    public static Metadata readMetadata(@NotNull IsoInput input) throws IOException
    {
        Metadata metadata = new Metadata();
        extract(input, metadata);
        return metadata;
    }

    /** Reads {@code input} with {@code readers} instead of {@link #ALL_READERS}; see {@link #compile}. */
    @NotNull
    public static Metadata readMetadata(@NotNull IsoInput input, @NotNull Iterable<? extends IsoBoxReader> readers) throws IOException
    {
        Metadata metadata = new Metadata();
        Reading reading = new Reading(metadata);
        compile(readers).read(input, reading.root);
        reading.finish(metadata);
        return metadata;
    }

    /** Reads {@code input} into {@code metadata}; see {@link #readMetadata(IsoInput)}. */
    public static void extract(@NotNull IsoInput input, @NotNull Metadata metadata) throws IOException
    {
        Reading reading = new Reading(metadata);
        DEFAULT_READERS.read(input, reading.root);
        reading.finish(metadata);
    }

    /** Reads a stream; see {@link #readMetadata(IsoInput)}. */
    @NotNull
    public static Metadata readMetadata(@NotNull InputStream inputStream)
    {
        Metadata metadata = new Metadata();
        Reading reading = new Reading(metadata);
        DEFAULT_READERS.read(inputStream, reading.root);
        reading.finish(metadata);
        return metadata;
    }

    /** Reads a file (see {@link #readMetadata(IsoInput)}) and the file system's metadata. */
    @NotNull
    public static Metadata readMetadata(@NotNull final File file) throws ImageProcessingException, IOException
    {
        Metadata metadata = readMetadata(IsoInput.of(file));
        new FileSystemMetadataReader().read(file, metadata);
        return metadata;
    }

    /**
     * One read: the MP4 readers fill directories of their own, into which problems also go by
     * way of the {@link QuickTimeDirectory}; {@link #finish} then copies everything over.
     */
    private static final class Reading
    {
        final QuickTimeDirectory directory = new QuickTimeDirectory();
        final Metadata read = new Metadata();
        final IsoBoxContext root;

        Reading(@NotNull Metadata metadata)
        {
            metadata.addDirectory(directory);
            root = IsoBoxContext.root(read, directory);
            Mp4MetadataReader.prepare(root);
        }

        void finish(@NotNull Metadata metadata)
        {
            QuickTimeDirectories.convert(read, metadata, directory);
        }
    }
}
