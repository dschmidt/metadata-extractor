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

import com.drew.lang.SequentialByteArrayReader;
import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Items of a {@code meta} box (ISO/IEC 14496-12, 8.11): {@code iinf} describes them and
 * {@code iloc} locates their data in the file or in {@code idat}. HEIF images are items, and so
 * are Exif and XMP in HEIF; an MP4 file can carry a {@code meta} with items too.
 * <p>
 * {@link #readers} reads the item boxes, asks the walk for the data of the items an
 * {@link ItemReader} accepts (see {@link IsoBoxContext#requestRange}) and hands each item to
 * the item readers as soon as all of its data is in.
 */
public final class IsoItems
{
    private IsoItems() {}

    /** An item, as {@code infe} describes it. */
    public static final class Item
    {
        public final long id;
        /** The item type, such as {@code hvc1}, {@code Exif} or {@code mime}. */
        @NotNull
        public final String type;
        @NotNull
        public final String name;
        /** The content type of a {@code mime} item, such as {@code application/rdf+xml}. */
        @Nullable
        public final String contentType;

        public Item(long id, @NotNull String type, @NotNull String name, @Nullable String contentType)
        {
            this.id = id;
            this.type = type;
            this.name = name;
            this.contentType = contentType;
        }
    }

    /** Reads the data of items, which {@code iloc} locates rather than a box path. */
    public interface ItemReader
    {
        /** Whether to read the data of {@code item}; only accepted items are read from the input. */
        boolean accepts(@NotNull Item item);

        /** Reads the data of {@code item}, all of its extents joined; {@code file} is the file context. */
        void readItem(@NotNull IsoBoxContext file, @NotNull Item item, @NotNull SequentialReader data) throws IOException;
    }

    /** Where {@code iloc} puts an item's data: extents in the file (method 0) or in {@code idat} (method 1). */
    private static final class Location
    {
        int constructionMethod;
        final List<long[]> extents = new ArrayList<long[]>();
    }

    /** The items of one {@code meta} box. */
    private static final class Meta
    {
        final Map<Long, Item> items = new LinkedHashMap<Long, Item>();
        final Map<Long, Location> locations = new HashMap<Long, Location>();
        @Nullable
        byte[] idat;
    }

    /**
     * The readers for the items of the {@code meta} boxes at {@code metaPaths}, read by
     * {@code itemReaders}, in a walk on {@link IsoStandardLayout} or a layout extending it.
     */
    @NotNull
    public static IsoBoxReader readers(@NotNull final List<? extends ItemReader> itemReaders, @NotNull String... metaPaths)
    {
        final List<String> paths = new ArrayList<String>();
        for (String meta : metaPaths) {
            paths.add(meta + "/iinf/infe");
            paths.add(meta + "/iloc");
            paths.add(meta + "/idat");
        }
        return new IsoBoxReader()
        {
            @NotNull
            @Override
            public Iterable<String> getBoxPaths()
            {
                return paths;
            }

            @Override
            public void readBox(@NotNull IsoBoxContext box) throws IOException
            {
                IsoBoxContext metaBox = box.ancestor("meta");
                if (metaBox == null)
                    return;
                Meta meta = metaBox.get(Meta.class);
                if (meta == null) {
                    final Meta items = new Meta();
                    meta = items;
                    metaBox.put(Meta.class, items);
                    metaBox.whenDone(new IsoBoxReaders.Callback()
                    {
                        @Override
                        public void read(@NotNull IsoBoxContext done)
                        {
                            request(done, items, itemReaders);
                        }
                    });
                }
                String type = box.box().type;
                if (type.equals("infe")) {
                    Item item = readItemInfo(box.payload());
                    if (item != null)
                        meta.items.put(item.id, item);
                } else if (type.equals("iloc")) {
                    readLocations(box.payload(), meta.locations);
                } else {
                    meta.idat = box.payloadBytes();
                }
            }
        };
    }

    @Nullable
    private static Item readItemInfo(@NotNull SequentialReader reader) throws IOException
    {
        int version = reader.getUInt8();
        reader.skip(3);
        if (version < 2) {
            long id = reader.getUInt16();
            reader.skip(2);
            String name = string(reader);
            return new Item(id, "mime", name, string(reader));
        }
        long id = version == 2 ? reader.getUInt16() : reader.getUInt32();
        reader.skip(2);
        String type = reader.getString(4, StandardCharsets.ISO_8859_1);
        String name = string(reader);
        return new Item(id, type, name, type.equals("mime") ? string(reader) : null);
    }

    @NotNull
    private static String string(@NotNull SequentialReader reader) throws IOException
    {
        return reader.getNullTerminatedString((int) Math.min(reader.available(), Integer.MAX_VALUE), StandardCharsets.UTF_8, false);
    }

    private static void readLocations(@NotNull SequentialReader reader, @NotNull Map<Long, Location> locations) throws IOException
    {
        int version = reader.getUInt8();
        reader.skip(3);
        int sizes = reader.getUInt8();
        int offsetSize = sizes >> 4;
        int lengthSize = sizes & 0x0F;
        sizes = reader.getUInt8();
        int baseOffsetSize = sizes >> 4;
        int indexSize = version == 1 || version == 2 ? sizes & 0x0F : 0;
        long count = version < 2 ? reader.getUInt16() : reader.getUInt32();
        for (long i = 0; i < count; i++) {
            long id = version < 2 ? reader.getUInt16() : reader.getUInt32();
            Location location = new Location();
            if (version == 1 || version == 2)
                location.constructionMethod = reader.getUInt16() & 0x0F;
            reader.skip(2);
            long baseOffset = number(reader, baseOffsetSize);
            int extents = reader.getUInt16();
            for (int e = 0; e < extents; e++) {
                number(reader, indexSize);
                long offset = number(reader, offsetSize);
                location.extents.add(new long[] {baseOffset + offset, number(reader, lengthSize)});
            }
            locations.put(id, location);
        }
    }

    private static long number(@NotNull SequentialReader reader, int size) throws IOException
    {
        switch (size) {
            case 0:
                return 0;
            case 4:
                return reader.getUInt32();
            case 8:
                return reader.getInt64();
            default:
                throw new IOException("Invalid field size " + size + " in 'iloc'");
        }
    }

    private static boolean accepted(@NotNull List<? extends ItemReader> itemReaders, @NotNull Item item)
    {
        for (ItemReader reader : itemReaders) {
            if (reader.accepts(item))
                return true;
        }
        return false;
    }

    /** Once {@code meta} is read: hands over the accepted items in {@code idat}, and asks for those in the file. */
    private static void request(@NotNull IsoBoxContext meta, @NotNull Meta items, @NotNull final List<? extends ItemReader> itemReaders)
    {
        final IsoBoxContext file = meta.root();
        for (final Item item : items.items.values()) {
            Location location = items.locations.get(item.id);
            if (location == null || location.extents.isEmpty() || !accepted(itemReaders, item))
                continue;
            if (location.constructionMethod == 1) {
                byte[] idat = items.idat;
                ByteArrayOutputStream joined = new ByteArrayOutputStream();
                for (long[] extent : location.extents) {
                    if (idat == null || extent[0] < 0 || extent[1] < 0 || extent[0] + extent[1] > idat.length) {
                        file.addError("Item " + item.id + " lies outside 'idat'");
                        joined = null;
                        break;
                    }
                    joined.write(idat, (int) extent[0], (int) extent[1]);
                }
                if (joined != null)
                    dispatch(file, item, joined.toByteArray(), itemReaders);
                continue;
            }
            if (location.constructionMethod != 0)
                continue;
            final byte[][] parts = new byte[location.extents.size()][];
            final int[] missing = {parts.length};
            for (int i = 0; i < parts.length; i++) {
                final int part = i;
                final long[] extent = location.extents.get(i);
                meta.requestRange(extent[0], extent[1], new IsoBoxContext.RangeReader()
                {
                    @Override
                    public void read(@NotNull IsoBoxContext box, @NotNull SequentialReader data) throws IOException
                    {
                        parts[part] = data.getBytes((int) extent[1]);
                        if (--missing[0] > 0)
                            return;
                        ByteArrayOutputStream joined = new ByteArrayOutputStream();
                        for (byte[] bytes : parts)
                            joined.write(bytes, 0, bytes.length);
                        dispatch(file, item, joined.toByteArray(), itemReaders);
                    }
                });
            }
        }
    }

    private static void dispatch(@NotNull IsoBoxContext file, @NotNull Item item, @NotNull byte[] data, @NotNull List<? extends ItemReader> itemReaders)
    {
        for (ItemReader reader : itemReaders) {
            if (!reader.accepts(item))
                continue;
            try {
                reader.readItem(file, item, new SequentialByteArrayReader(data));
            } catch (IOException e) {
                file.addError("Error reading item " + item.id + ": " + e.getMessage());
            }
        }
    }
}
