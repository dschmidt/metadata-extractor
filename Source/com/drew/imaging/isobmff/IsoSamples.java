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

import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Samples of the tracks in {@code moov} (ISO/IEC 14496-12, 8.7) and of track fragments in
 * {@code moof} (8.8): the sample tables or track runs locate each sample's data by input
 * position, usually in {@code mdat}. Timed metadata such as GPS tracks, Apple's {@code mebx} or
 * Canon's {@code CTMD} lives in samples.
 * <p>
 * {@link #readers} reads the handler, sample description and sample tables of each track, and
 * the track runs of each fragment, and asks the walk for the samples of the tracks a
 * {@link SampleReader} accepts (see {@link IsoBoxContext#requestRange}).
 */
public final class IsoSamples
{
    private IsoSamples() {}

    /** A track, as far as its boxes say before its samples are read. */
    public static final class Track
    {
        @Nullable
        String handlerType;
        @Nullable
        String format;
        int index;
        long id;

        Track() {}

        /** The handler type from {@code mdia/hdlr}, such as {@code vide}, {@code soun} or {@code meta}. */
        @Nullable
        public String getHandlerType()
        {
            return handlerType;
        }

        /** The format of the first sample description, such as {@code avc1}, {@code mebx} or {@code CTMD}. */
        @Nullable
        public String getFormat()
        {
            return format;
        }

        /** The track's position in {@code moov}, from 0. */
        public int getIndex()
        {
            return index;
        }

        /** The track ID from {@code tkhd}. */
        public long getId()
        {
            return id;
        }

        @Nullable
        long[] chunkOffsets;
        @Nullable
        long[] sampleSizes;
        @Nullable
        long[] chunkRuns;
        /** The default sample size of fragments, from {@code trex}. */
        long defaultSampleSize;
        int samples;
    }

    /** Reads the samples of the tracks it accepts. */
    public interface SampleReader
    {
        /** Whether to read the samples of {@code track}; only accepted tracks are read from the input. */
        boolean accepts(@NotNull Track track);

        /** Reads sample {@code index} of {@code track}, counted over the whole file; {@code file} is the file context. */
        void readSample(@NotNull IsoBoxContext file, @NotNull Track track, int index, @NotNull SequentialReader data) throws IOException;
    }

    /** The tracks of the file by ID, on the file context. */
    private static final class Tracks
    {
        final Map<Long, Track> byId = new HashMap<Long, Track>();
        int count;
    }

    /** Where the samples of a track fragment are, from {@code tfhd}; on the {@code traf} context. */
    private static final class Fragment
    {
        @Nullable
        Track track;
        long nextDataOffset;
        long defaultSampleSize;
    }

    /** Where the data of the next track fragment of a {@code moof} starts, on the {@code moof} context. */
    private static final class Movie
    {
        long nextDataOffset;
    }

    private static final String STBL = "moov/trak/mdia/minf/stbl/";

    /** The readers for the tracks and fragments of the file; at most {@code maxSamples} samples are read per track. */
    @NotNull
    public static IsoBoxReader readers(@NotNull final SampleReader reader, final int maxSamples)
    {
        final List<String> paths = Arrays.asList("moov/trak/tkhd", "moov/trak/mdia/hdlr", STBL + "stsd", STBL + "stsc",
            STBL + "stsz", STBL + "stz2", STBL + "stco", STBL + "co64", "moov/mvex/trex", "moof/traf/tfhd", "moof/traf/trun");
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
                SequentialReader payload = box.payload();
                int version = payload.getUInt8();
                int flags = payload.getUInt16() << 8 | payload.getUInt8();
                String type = box.box().type;
                Tracks tracks = tracks(box);
                if (type.equals("trex")) {
                    Track track = tracks.byId.get(payload.getUInt32());
                    if (track != null) {
                        payload.skip(8);
                        track.defaultSampleSize = payload.getUInt32();
                    }
                    return;
                }
                if (type.equals("tfhd")) {
                    readFragmentHeader(box, payload, flags, tracks);
                    return;
                }
                if (type.equals("trun")) {
                    readTrackRun(box, payload, version, flags, reader, maxSamples);
                    return;
                }
                IsoBoxContext trak = box.ancestor("trak");
                if (trak == null)
                    return;
                Track track = trak.get(Track.class);
                if (track == null) {
                    final Track created = new Track();
                    created.index = tracks.count++;
                    trak.put(Track.class, created);
                    trak.whenDone(new IsoBoxReaders.Callback()
                    {
                        @Override
                        public void read(@NotNull IsoBoxContext done)
                        {
                            request(done, created, reader);
                        }
                    });
                    track = created;
                }
                if (type.equals("tkhd")) {
                    payload.skip(version == 1 ? 16 : 8);
                    track.id = payload.getUInt32();
                    tracks.byId.put(track.id, track);
                } else if (type.equals("hdlr")) {
                    payload.skip(4);
                    track.handlerType = payload.getString(4);
                } else if (type.equals("stsd")) {
                    if (payload.getUInt32() > 0) {
                        payload.skip(4);
                        track.format = payload.getString(4);
                    }
                } else if (!reader.accepts(track)) {
                    return;
                } else if (type.equals("stsz")) {
                    long size = payload.getUInt32();
                    int count = (int) Math.min(payload.getUInt32(), maxSamples);
                    long[] sizes = new long[count];
                    for (int i = 0; i < count; i++)
                        sizes[i] = size != 0 ? size : payload.getUInt32();
                    track.sampleSizes = sizes;
                } else if (type.equals("stz2")) {
                    payload.skip(3);
                    int fieldSize = payload.getUInt8();
                    if (fieldSize != 4 && fieldSize != 8 && fieldSize != 16)
                        throw new IOException("Invalid field size " + fieldSize + " in 'stz2'");
                    int count = (int) Math.min(payload.getUInt32(), maxSamples);
                    long[] sizes = new long[count];
                    for (int i = 0; i < count; i++) {
                        if (fieldSize == 16) {
                            sizes[i] = payload.getUInt16();
                        } else if (fieldSize == 8) {
                            sizes[i] = payload.getUInt8();
                        } else {
                            int pair = payload.getUInt8();
                            sizes[i] = pair >> 4;
                            if (++i < count)
                                sizes[i] = pair & 0x0F;
                        }
                    }
                    track.sampleSizes = sizes;
                } else if (type.equals("stsc")) {
                    int count = (int) Math.min(payload.getUInt32(), maxSamples);
                    long[] runs = new long[count * 2];
                    for (int i = 0; i < count; i++) {
                        runs[i * 2] = payload.getUInt32();
                        runs[i * 2 + 1] = payload.getUInt32();
                        payload.skip(4);
                    }
                    track.chunkRuns = runs;
                } else {
                    int count = (int) Math.min(payload.getUInt32(), maxSamples);
                    long[] offsets = new long[count];
                    for (int i = 0; i < count; i++)
                        offsets[i] = type.equals("co64") ? payload.getInt64() : payload.getUInt32();
                    track.chunkOffsets = offsets;
                }
            }
        };
    }

    @NotNull
    private static Tracks tracks(@NotNull IsoBoxContext box)
    {
        Tracks tracks = box.root().get(Tracks.class);
        if (tracks == null) {
            tracks = new Tracks();
            box.root().put(Tracks.class, tracks);
        }
        return tracks;
    }

    private static void readFragmentHeader(@NotNull IsoBoxContext box, @NotNull SequentialReader payload, int flags,
                                           @NotNull Tracks tracks) throws IOException
    {
        IsoBoxContext traf = box.parent();
        IsoBoxContext moof = box.ancestor("moof");
        if (traf == null || moof == null)
            return;
        Movie movie = moof.get(Movie.class);
        if (movie == null) {
            movie = new Movie();
            movie.nextDataOffset = moof.box().offset;
            moof.put(Movie.class, movie);
        }
        Fragment fragment = new Fragment();
        fragment.track = tracks.byId.get(payload.getUInt32());
        // without an explicit base, the first fragment starts at the moof box and later ones after the data before them
        fragment.nextDataOffset = (flags & 0x020000) != 0 ? moof.box().offset : movie.nextDataOffset;
        if ((flags & 0x000001) != 0)
            fragment.nextDataOffset = payload.getInt64();
        if ((flags & 0x000002) != 0)
            payload.skip(4);
        if ((flags & 0x000008) != 0)
            payload.skip(4);
        fragment.defaultSampleSize = (flags & 0x000010) != 0 ? payload.getUInt32()
            : fragment.track == null ? 0 : fragment.track.defaultSampleSize;
        traf.put(Fragment.class, fragment);
    }

    private static void readTrackRun(@NotNull IsoBoxContext box, @NotNull SequentialReader payload, int version, int flags,
                                     @NotNull final SampleReader reader, int maxSamples) throws IOException
    {
        IsoBoxContext traf = box.parent();
        IsoBoxContext moof = box.ancestor("moof");
        Fragment fragment = traf == null ? null : traf.get(Fragment.class);
        if (fragment == null || moof == null)
            return;
        Movie movie = moof.get(Movie.class);
        long count = payload.getUInt32();
        long offset = fragment.nextDataOffset;
        if ((flags & 0x000001) != 0)
            offset = moof.box().offset + payload.getInt32();
        if ((flags & 0x000004) != 0)
            payload.skip(4);
        final Track track = fragment.track;
        boolean accepted = track != null && reader.accepts(track);
        for (long i = 0; i < count; i++) {
            if ((flags & 0x000100) != 0)
                payload.skip(4);
            long size = (flags & 0x000200) != 0 ? payload.getUInt32() : fragment.defaultSampleSize;
            if ((flags & 0x000400) != 0)
                payload.skip(4);
            if ((flags & 0x000800) != 0)
                payload.skip(4);
            if (accepted && track.samples < maxSamples)
                requestSample(box, track, track.samples++, offset, size, reader);
            offset += size;
        }
        fragment.nextDataOffset = offset;
        if (movie != null)
            movie.nextDataOffset = offset;
    }

    /** Asks for the samples of {@code track} once its {@code trak} is read. */
    private static void request(@NotNull IsoBoxContext box, @NotNull Track track, @NotNull SampleReader reader)
    {
        long[] offsets = track.chunkOffsets;
        long[] sizes = track.sampleSizes;
        long[] runs = track.chunkRuns;
        if (offsets == null || sizes == null || runs == null || runs.length == 0)
            return;
        for (int chunk = 0; chunk < offsets.length && track.samples < sizes.length; chunk++) {
            long perChunk = 0;
            for (int run = 0; run < runs.length; run += 2) {
                if (runs[run] - 1 <= chunk)
                    perChunk = runs[run + 1];
            }
            long offset = offsets[chunk];
            for (long i = 0; i < perChunk && track.samples < sizes.length; i++) {
                long size = sizes[track.samples];
                requestSample(box, track, track.samples++, offset, size, reader);
                offset += size;
            }
        }
    }

    private static void requestSample(@NotNull IsoBoxContext box, @NotNull final Track track, final int index, long offset, long size,
                                      @NotNull final SampleReader reader)
    {
        box.requestRange(offset, size, new IsoBoxContext.RangeReader()
        {
            @Override
            public void read(@NotNull IsoBoxContext data, @NotNull SequentialReader bytes) throws IOException
            {
                reader.readSample(data.root(), track, index, bytes);
            }
        });
    }
}
