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
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.Metadata;
import com.drew.metadata.file.FileSystemMetadataReader;
import com.drew.metadata.mov.QuickTimeDirectory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Obtains metadata from QuickTime files into the directories of {@code com.drew.metadata.mov},
 * with box readers registered by path; see {@link IsoBoxReaders} for how boxes are walked.
 *
 * @author Payton Garland
 */
public class QuickTimeMetadataReader
{
    /**
     * The readers {@link #readMetadata(IsoInput)} uses. Pass them, with readers of your own, to
     * {@link #compile} to read further boxes.
     */
    public static final List<IsoBoxReader> ALL_READERS = QuickTimeReaders.ALL_READERS;

    private static final IsoBoxReaders DEFAULT_READERS = compile(ALL_READERS);

    /** Prepares a walk over the QuickTime structure that calls {@code readers}; build it once and reuse it. */
    @NotNull
    public static IsoBoxReaders compile(@NotNull Iterable<? extends IsoBoxReader> readers)
    {
        return new IsoBoxReaders(QuickTimeReaders.LAYOUT, readers);
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
        compile(readers).read(input, newRoot(metadata, null));
        return metadata;
    }

    /** Reads {@code input} into {@code metadata}; see {@link #readMetadata(IsoInput)}. */
    public static void extract(@NotNull IsoInput input, @NotNull Metadata metadata) throws IOException
    {
        DEFAULT_READERS.read(input, newRoot(metadata, null));
    }

    /** Reads a stream; see {@link #readMetadata(IsoInput)}. */
    @NotNull
    public static Metadata readMetadata(@NotNull InputStream inputStream)
    {
        Metadata metadata = new Metadata();
        DEFAULT_READERS.read(inputStream, newRoot(metadata, null));
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
     * The file context for reading into {@code metadata}: it adds a {@link QuickTimeDirectory}.
     * Problems go to {@code errors}, or to the {@link QuickTimeDirectory} when it is {@code null}.
     */
    @NotNull
    public static IsoBoxContext newRoot(@NotNull Metadata metadata, @Nullable IsoBoxContext.ErrorHandler errors)
    {
        QuickTimeDirectory directory = new QuickTimeDirectory();
        metadata.addDirectory(directory);
        IsoBoxContext root = errors == null ? IsoBoxContext.root(metadata, directory) : IsoBoxContext.root(metadata, errors);
        root.put(QuickTimeDirectory.class, directory);
        return root;
    }
}
