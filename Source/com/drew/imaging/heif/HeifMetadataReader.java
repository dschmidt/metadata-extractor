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
package com.drew.imaging.heif;

import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.imaging.isobmff.IsoItems;
import com.drew.imaging.isobmff.IsoStandardLayout;
import com.drew.lang.RandomAccessStreamReader;
import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifReader;
import com.drew.metadata.file.FileSystemMetadataReader;
import com.drew.metadata.heif.HeifDirectory;
import com.drew.metadata.xmp.XmpReader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Obtains metadata from HEIF files (HEIC, AVIF), with box readers registered by path and item
 * readers registered by item type; see {@link IsoBoxReaders} for how boxes are walked.
 */
public class HeifMetadataReader
{
    /** Reads {@code Exif} items into Exif directories. */
    public static final IsoItems.ItemReader EXIF_ITEM_READER = new IsoItems.ItemReader()
    {
        @Override
        public boolean accepts(@NotNull IsoItems.Item item)
        {
            return item.type.equals("Exif");
        }

        @Override
        public void readItem(@NotNull IsoBoxContext file, @NotNull IsoItems.Item item, @NotNull SequentialReader data)
            throws IOException
        {
            // ISO/IEC 23008-12:2017 Annex A: the first 4 bytes are the offset of the TIFF header
            long tiffHeaderOffset = data.getUInt32();
            if (tiffHeaderOffset > data.available())
                return;
            data.skip(tiffHeaderOffset);
            byte[] tiff = data.getBytes(data.available());
            new ExifReader().extract(new RandomAccessStreamReader(new ByteArrayInputStream(tiff)), file.metadata());
        }
    };

    /** Reads {@code mime} items of type {@code application/rdf+xml} into an XMP directory. */
    public static final IsoItems.ItemReader XMP_ITEM_READER = new IsoItems.ItemReader()
    {
        @Override
        public boolean accepts(@NotNull IsoItems.Item item)
        {
            return item.type.equals("mime") && "application/rdf+xml".equals(item.contentType);
        }

        @Override
        public void readItem(@NotNull IsoBoxContext file, @NotNull IsoItems.Item item, @NotNull SequentialReader data)
            throws IOException
        {
            new XmpReader().extract(data.getBytes(data.available()), file.metadata());
        }
    };

    /** The item readers {@link #readMetadata(InputStream)} uses. */
    public static final List<IsoItems.ItemReader> ALL_ITEM_READERS =
        Collections.unmodifiableList(Arrays.asList(EXIF_ITEM_READER, XMP_ITEM_READER));

    /** The box readers {@link #readMetadata(InputStream)} uses: file type, handler, item and property boxes. */
    public static final List<IsoBoxReader> ALL_READERS = HeifReaders.ALL_READERS;

    private static final IsoBoxReaders DEFAULT_READERS = compile(ALL_READERS, ALL_ITEM_READERS);

    /**
     * The readers of {@code itemReaders} for the items of the file's {@code meta}, for a walk
     * compiled for another format, such as an image sequence read with MP4 readers too.
     */
    @NotNull
    public static IsoBoxReader itemReaders(@NotNull List<? extends IsoItems.ItemReader> itemReaders)
    {
        return IsoItems.readers(itemReaders, "meta");
    }

    /** Prepares a walk that calls {@code readers} for boxes and {@code itemReaders} for items; build it once and reuse it. */
    @NotNull
    public static IsoBoxReaders compile(@NotNull Iterable<? extends IsoBoxReader> readers,
                                        @NotNull Iterable<? extends IsoItems.ItemReader> itemReaders)
    {
        List<IsoItems.ItemReader> items = new java.util.ArrayList<IsoItems.ItemReader>();
        for (IsoItems.ItemReader reader : itemReaders)
            items.add(reader);
        List<IsoBoxReader> boxes = new java.util.ArrayList<IsoBoxReader>();
        for (IsoBoxReader reader : readers)
            boxes.add(reader);
        boxes.add(itemReaders(items));
        return new IsoBoxReaders(IsoStandardLayout.INSTANCE, boxes);
    }

    /**
     * Reads {@code inputStream}. Item data that comes before {@code meta} is kept in memory
     * until {@code meta} is read, up to {@link IsoBoxReaders#DEFAULT_MAX_BUFFERED_PAYLOAD};
     * beyond that it is reported, and {@link #readMetadata(File)} reads it without buffering.
     */
    @NotNull
    public static Metadata readMetadata(@NotNull InputStream inputStream)
    {
        Metadata metadata = new Metadata();
        extract(inputStream, metadata);
        return metadata;
    }

    /** Reads {@code file}, with item data read by position wherever it lies, and the file system's metadata. */
    @NotNull
    public static Metadata readMetadata(@NotNull File file) throws IOException
    {
        Metadata metadata = new Metadata();
        extract(file, metadata);
        new FileSystemMetadataReader().read(file, metadata);
        return metadata;
    }

    /** Reads {@code inputStream} into {@code metadata}; see {@link #readMetadata(InputStream)}. */
    public static void extract(@NotNull InputStream inputStream, @NotNull Metadata metadata)
    {
        DEFAULT_READERS.read(inputStream, newRoot(metadata, null));
    }

    /** Reads {@code file} into {@code metadata}, with item data read by position wherever it lies. */
    public static void extract(@NotNull File file, @NotNull Metadata metadata) throws IOException
    {
        DEFAULT_READERS.read(file, newRoot(metadata, null));
    }

    /**
     * The file context for reading into {@code metadata}: it adds a {@link HeifDirectory}.
     * Problems go to {@code errors}, or to the {@link HeifDirectory} when it is {@code null}.
     */
    @NotNull
    public static IsoBoxContext newRoot(@NotNull Metadata metadata, @Nullable IsoBoxContext.ErrorHandler errors)
    {
        HeifDirectory directory = new HeifDirectory();
        metadata.addDirectory(directory);
        IsoBoxContext root = errors == null ? IsoBoxContext.root(metadata, directory) : IsoBoxContext.root(metadata, errors);
        root.put(HeifReaders.Directories.class, new HeifReaders.Directories(directory));
        return root;
    }
}
