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
package com.drew.imaging.isobmff;

import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Samples of the tracks in {@code moov} (ISO/IEC 14496-12, 8.7) and of track fragments in
 * {@code moof} (8.8): the sample tables or track runs locate each sample's data by input
 * position, usually in {@code mdat}, and give its decoding time. Timed metadata such as
 * subtitles, GPS tracks, Apple's {@code mebx} or Canon's {@code CTMD} lives in samples.
 * <p>
 * {@link #readers} reads the tracks and their sample tables, and the track runs of each
 * fragment, and once {@code moov} is read asks the walk for the samples of the tracks a
 * {@link SampleReader} accepts (see {@link IsoBoxContext#requestRange}). Each sample carries its
 * decoding time and, from the track's edit list, the time at which it is presented.
 */
public final class IsoSamples
{
    private IsoSamples() {}

    /** A track, as far as its boxes say. */
    public static final class Track
    {
        @Nullable
        String handlerType;
        @Nullable
        String format;
        int index;
        long id;
        long timescale;
        int languageCode = -1;
        @Nullable
        Set<String> referencedAs;
        @Nullable
        Map<String, long[]> references;

        /** Whether the reader accepted the track when its sample description was read; null before that. */
        @Nullable
        Boolean wanted;
        boolean accepted;
        boolean lost;
        @Nullable
        long[] chunkOffsets;
        @Nullable
        SampleSizes sampleSizes;
        @Nullable
        long[] chunkRuns;
        @Nullable
        long[] timeRuns;
        /** The edit list: pairs of segment duration in the movie's time scale and media time, -1 for an empty edit. */
        @Nullable
        long[] edits;
        @Nullable
        byte[] sampleEntry;
        long defaultSampleDuration;
        long defaultSampleSize;
        int samples;
        long time;
        /** Whether the track holds more samples than are read; reported at the end of the walk. */
        boolean capped;

        Track() {}

        /**
         * The first sample entry of {@code stsd} after its size and type: six reserved bytes and
         * the data reference index, then the fields and boxes of the format, such as the
         * {@code keys} of a {@code mebx} entry; up to 64 KiB. {@code null} before {@code stsd} is read.
         */
        @Nullable
        public byte[] getSampleEntry()
        {
            return sampleEntry;
        }

        /** The handler type from {@code mdia/hdlr}, such as {@code vide}, {@code soun}, {@code text} or {@code meta}. */
        @Nullable
        public String getHandlerType()
        {
            return handlerType;
        }

        /** The format of the first sample description, such as {@code avc1}, {@code tx3g}, {@code mebx} or {@code CTMD}. */
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

        /** The time scale from {@code mdia/mdhd}: the units of a second in which the samples' times are given. */
        public long getTimescale()
        {
            return timescale;
        }

        /**
         * The language from {@code mdia/mdhd} as stored: three 5-bit letters of ISO 639-2/T
         * (0x55C4 is {@code und}), or a QuickTime Macintosh language code below 0x400; -1 if
         * not read.
         */
        public int getLanguageCode()
        {
            return languageCode;
        }

        /**
         * Whether another track refers to this one with a {@code tref} reference of
         * {@code type}, such as {@code chap} for a QuickTime chapter track. Known once
         * {@code moov} is read.
         */
        public boolean isReferencedAs(@NotNull String type)
        {
            return referencedAs != null && referencedAs.contains(type);
        }
    }

    /** One sample of a track. */
    public static final class Sample
    {
        private final int _index;
        private final long _time;
        private final long _presentationTime;
        private final long _duration;
        private final long _offset;
        private final long _size;

        Sample(int index, long time, long presentationTime, long duration, long offset, long size)
        {
            _index = index;
            _time = time;
            _presentationTime = presentationTime;
            _duration = duration;
            _offset = offset;
            _size = size;
        }

        /** The sample's number in its track, from 0, over the sample tables and then the fragments. */
        public int getIndex()
        {
            return _index;
        }

        /** The decoding time, in the track's time scale: the sample's position in the media, before any edit list. */
        public long getTime()
        {
            return _time;
        }

        /**
         * The time at which the sample is presented, in the track's time scale, with the track's
         * edit list applied: an empty edit at the start delays it, an edit starting inside the
         * media advances it. The same as {@link #getTime} without an edit list, and -1 when no
         * edit presents the sample.
         */
        public long getPresentationTime()
        {
            return _presentationTime;
        }

        /** The duration, in the track's time scale. */
        public long getDuration()
        {
            return _duration;
        }

        /** The position of the sample's data in the input. */
        public long getOffset()
        {
            return _offset;
        }

        /** The size of the sample's data. */
        public long getSize()
        {
            return _size;
        }
    }

    /** Reads the samples of the tracks it accepts. */
    public interface SampleReader
    {
        /**
         * Whether to read the samples of {@code track}. Called once the track's sample
         * description is read, to decide whether to keep its sample tables, and again once
         * {@code moov} is read, when {@link Track#isReferencedAs} is known; samples are read
         * only for a track accepted both times.
         */
        boolean accepts(@NotNull Track track);

        /**
         * Reads a sample, as the walk passes it: in input order, which is not always the order
         * of {@link Sample#getIndex()}. {@code file} is the file context.
         */
        void readSample(@NotNull IsoBoxContext file, @NotNull Track track, @NotNull Sample sample, @NotNull SequentialReader data)
            throws IOException;
    }

    /** Sizes from {@code stsz}, one for all samples or one each, or from {@code stz2}. */
    static final class SampleSizes
    {
        final int count;
        /** The number of sizes the box holds, before the cap. */
        final long present;
        private final long _fixed;
        @Nullable
        private final long[] _sizes;

        SampleSizes(int count, long present, long fixed, @Nullable long[] sizes)
        {
            this.count = count;
            this.present = present;
            _fixed = fixed;
            _sizes = sizes;
        }

        long get(int index)
        {
            return _sizes == null ? _fixed : _sizes[index];
        }
    }

    /** The tracks of the file, on the file context. */
    private static final class Tracks
    {
        final List<Track> all = new ArrayList<Track>();
        final Map<Long, Track> byId = new HashMap<Long, Track>();
        /** The movie's time scale from {@code mvhd}, in which edit lists give their segment durations. */
        long movieTimescale;
        boolean requested;
    }

    /** The most of a sample entry kept for {@link Track#getSampleEntry}. */
    private static final int MAX_SAMPLE_ENTRY = 64 * 1024;

    /** Where the samples of a track fragment are, from {@code tfhd}; on the {@code traf} context. */
    private static final class Fragment
    {
        @Nullable
        Track track;
        boolean located;
        long base;
        long next;
        long defaultSampleDuration;
        long defaultSampleSize;
    }

    /** Where the data of the next track fragment of a {@code moof} starts; on the {@code moof} context. */
    private static final class Movie
    {
        long nextDataOffset;
        boolean located = true;
    }

    private static final String TRAK = "moov/trak/";
    /** The sample tables, read only for tracks the sample reader wants. */
    private static final Set<String> TABLES = new HashSet<String>(Arrays.asList("stts", "stsc", "stsz", "stz2", "stco", "co64"));
    private static final String STBL = TRAK + "mdia/minf/stbl/";

    /** The readers for the tracks and fragments of the file; at most {@code maxSamples} samples are read per track. */
    @NotNull
    public static IsoBoxReader readers(@NotNull final SampleReader reader, final int maxSamples)
    {
        final List<String> paths = Arrays.asList("moov/mvhd", TRAK + "tkhd", TRAK + "tref/*", TRAK + "edts/elst",
            TRAK + "mdia/mdhd", TRAK + "mdia/hdlr",
            STBL + "stsd", STBL + "stts", STBL + "stsc", STBL + "stsz", STBL + "stz2", STBL + "stco", STBL + "co64",
            "moov/mvex/trex", "moof/traf/tfhd", "moof/traf/tfdt", "moof/traf/trun");
        return new IsoBoxReader()
        {
            @NotNull
            @Override
            public Iterable<String> getBoxPaths()
            {
                return paths;
            }

            /** The samples of {@code moov}'s tracks; a fragment's samples follow its {@code moof}. */
            @NotNull
            @Override
            public Iterable<String> getLocatingPaths()
            {
                return Collections.singletonList("moov");
            }

            @Override
            public void readBox(@NotNull IsoBoxContext box) throws IOException
            {
                Tracks tracks = tracks(box, reader, maxSamples);
                if (TABLES.contains(box.box().type)) {
                    IsoBoxContext trak = box.ancestor("trak");
                    if (trak == null || Boolean.FALSE.equals(track(trak, tracks).wanted))
                        return;
                }
                SequentialReader payload = box.payload();
                if (box.parent().box().type.equals("tref")) {
                    readReference(box, payload, tracks, maxSamples);
                    return;
                }
                int version = payload.getUInt8();
                int flags = payload.getUInt16() << 8 | payload.getUInt8();
                String type = box.box().type;
                if (type.equals("mvhd")) {
                    payload.skip(version == 1 ? 16 : 8);
                    tracks.movieTimescale = payload.getUInt32();
                } else if (type.equals("trex")) {
                    Track track = tracks.byId.get(payload.getUInt32());
                    if (track != null) {
                        payload.skip(4);
                        track.defaultSampleDuration = payload.getUInt32();
                        track.defaultSampleSize = payload.getUInt32();
                    }
                } else if (type.equals("tfhd")) {
                    readFragmentHeader(box, payload, flags, tracks);
                } else if (type.equals("tfdt")) {
                    Fragment fragment = box.parent().get(Fragment.class);
                    if (fragment == null || fragment.track == null)
                        return;
                    if (remaining(box) < (version == 1 ? 8 : 4)) {
                        fragment.track.lost = true;
                        throw new IOException("Shorter than its version declares");
                    }
                    fragment.track.time = version == 1 ? payload.getInt64() : payload.getUInt32();
                } else if (type.equals("trun")) {
                    readTrackRun(box, payload, flags, reader, maxSamples);
                } else {
                    readTrackBox(box, payload, version, type, tracks, reader, maxSamples);
                }
            }
        };
    }

    /** The tracks of the file, created with the first track box, when the samples are also scheduled for when {@code moov} is read. */
    @NotNull
    private static Tracks tracks(@NotNull IsoBoxContext box, @NotNull final SampleReader reader, final int maxSamples)
    {
        Tracks tracks = box.root().get(Tracks.class);
        if (tracks == null) {
            final Tracks created = new Tracks();
            box.root().put(Tracks.class, created);
            box.root().whenDone(new IsoBoxReaders.Callback()
            {
                @Override
                public void read(@NotNull IsoBoxContext root)
                {
                    for (Track track : created.all) {
                        if (track.capped)
                            root.addError("Track " + track.id + " has more than " + maxSamples + " samples; the rest are not read");
                    }
                }
            });
            IsoBoxContext moov = box.ancestor("moov");
            if (moov != null) {
                moov.whenDone(new IsoBoxReaders.Callback()
                {
                    @Override
                    public void read(@NotNull IsoBoxContext done)
                    {
                        requestTables(done, created, reader, maxSamples);
                    }
                });
            }
            tracks = created;
        }
        return tracks;
    }

    /** The track {@code box} belongs to, created with the first of its boxes. */
    @NotNull
    private static Track track(@NotNull IsoBoxContext trak, @NotNull Tracks tracks)
    {
        Track track = trak.get(Track.class);
        if (track == null) {
            track = new Track();
            track.index = tracks.all.size();
            tracks.all.add(track);
            trak.put(Track.class, track);
        }
        return track;
    }

    /** A {@code tref} entry: the IDs of the tracks this one refers to, by reference type. */
    private static void readReference(@NotNull IsoBoxContext box, @NotNull SequentialReader payload, @NotNull Tracks tracks,
                                      int maxSamples) throws IOException
    {
        IsoBoxContext trak = box.ancestor("trak");
        if (trak == null)
            return;
        Track track = track(trak, tracks);
        int count = (int) Math.min(remaining(box) / 4, maxSamples);
        long[] ids = new long[count];
        for (int i = 0; i < count; i++)
            ids[i] = payload.getUInt32();
        if (track.references == null)
            track.references = new HashMap<String, long[]>();
        track.references.put(box.box().type, ids);
    }

    private static void readTrackBox(@NotNull IsoBoxContext box, @NotNull SequentialReader payload, int version, @NotNull String type,
                                     @NotNull Tracks tracks, @NotNull SampleReader reader, int maxSamples) throws IOException
    {
        IsoBoxContext trak = box.ancestor("trak");
        if (trak == null)
            return;
        Track track = track(trak, tracks);
        if (type.equals("tkhd")) {
            payload.skip(version == 1 ? 16 : 8);
            track.id = payload.getUInt32();
            tracks.byId.put(track.id, track);
        } else if (type.equals("mdhd")) {
            payload.skip(version == 1 ? 16 : 8);
            track.timescale = payload.getUInt32();
            payload.skip(version == 1 ? 8 : 4);
            track.languageCode = payload.getUInt16();
        } else if (type.equals("hdlr")) {
            payload.skip(4);
            track.handlerType = payload.getString(4);
        } else if (type.equals("stsd")) {
            if (payload.getUInt32() > 0) {
                long entrySize = payload.getUInt32();
                track.format = payload.getString(4);
                if (entrySize >= 8)
                    track.sampleEntry = payload.getBytes((int) Math.min(Math.min(entrySize - 8, remaining(box)), MAX_SAMPLE_ENTRY));
            }
            track.wanted = reader.accepts(track);
        } else if (type.equals("elst")) {
            int entrySize = version == 1 ? 20 : 12;
            int count = (int) Math.min(Math.min(payload.getUInt32(), maxSamples), remaining(box) / entrySize);
            long[] edits = new long[count * 2];
            for (int i = 0; i < count; i++) {
                edits[i * 2] = version == 1 ? payload.getInt64() : payload.getUInt32();
                edits[i * 2 + 1] = version == 1 ? payload.getInt64() : payload.getInt32();
                payload.skip(4); // media rate
            }
            track.edits = edits;
        } else if (!Boolean.FALSE.equals(track.wanted)) {
            readTable(box, payload, type, track, maxSamples);
        }
    }

    private static void readTable(@NotNull IsoBoxContext box, @NotNull SequentialReader payload, @NotNull String type,
                                  @NotNull Track track, int maxSamples) throws IOException
    {
        if (type.equals("stsz")) {
            long size = payload.getUInt32();
            long declared = payload.getUInt32();
            if (size != 0) {
                track.sampleSizes = new SampleSizes((int) Math.min(declared, maxSamples), declared, size, null);
                return;
            }
            long present = Math.min(declared, remaining(box) / 4);
            int count = (int) Math.min(present, maxSamples);
            long[] sizes = new long[count];
            for (int i = 0; i < count; i++)
                sizes[i] = payload.getUInt32();
            track.sampleSizes = new SampleSizes(count, present, 0, sizes);
        } else if (type.equals("stz2")) {
            payload.skip(3);
            int fieldSize = payload.getUInt8();
            if (fieldSize != 4 && fieldSize != 8 && fieldSize != 16)
                throw new IOException("Invalid field size " + fieldSize);
            long declared = payload.getUInt32();
            long present = Math.min(declared, remaining(box) * 8 / fieldSize);
            int count = (int) Math.min(present, maxSamples);
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
            track.sampleSizes = new SampleSizes(count, present, 0, sizes);
        } else if (type.equals("stsc")) {
            track.chunkRuns = pairs(box, payload, 12, maxSamples);
        } else if (type.equals("stts")) {
            track.timeRuns = pairs(box, payload, 8, maxSamples);
        } else {
            boolean wide = type.equals("co64");
            int count = (int) Math.min(Math.min(payload.getUInt32(), maxSamples), remaining(box) / (wide ? 8 : 4));
            long[] offsets = new long[count];
            for (int i = 0; i < count; i++)
                offsets[i] = wide ? payload.getInt64() : payload.getUInt32();
            track.chunkOffsets = offsets;
        }
    }

    /** The first two 32-bit fields of each entry of {@code stsc} or {@code stts}. */
    @NotNull
    private static long[] pairs(@NotNull IsoBoxContext box, @NotNull SequentialReader payload, int entrySize, int maxSamples) throws IOException
    {
        int count = (int) Math.min(Math.min(payload.getUInt32(), maxSamples), remaining(box) / entrySize);
        long[] pairs = new long[count * 2];
        for (int i = 0; i < count; i++) {
            pairs[i * 2] = payload.getUInt32();
            pairs[i * 2 + 1] = payload.getUInt32();
            payload.skip(entrySize - 8);
        }
        return pairs;
    }

    /** The bytes of {@code box}'s payload not read yet. */
    private static long remaining(@NotNull IsoBoxContext box) throws IOException
    {
        long size = box.box().payloadSize;
        return size == -1 ? Long.MAX_VALUE : Math.max(0, size - box.payload().getPosition());
    }

    /** Once {@code moov} is read: decides on each track and asks for the samples its tables locate. */
    private static void requestTables(@NotNull IsoBoxContext moov, @NotNull Tracks tracks, @NotNull SampleReader reader, int maxSamples)
    {
        if (tracks.requested)
            return;
        tracks.requested = true;
        for (Track track : tracks.all) {
            if (track.references == null)
                continue;
            for (Map.Entry<String, long[]> reference : track.references.entrySet()) {
                for (long id : reference.getValue()) {
                    Track target = tracks.byId.get(id);
                    if (target == null)
                        continue;
                    if (target.referencedAs == null)
                        target.referencedAs = new HashSet<String>(1);
                    target.referencedAs.add(reference.getKey());
                }
            }
        }
        for (Track track : tracks.all) {
            track.accepted = !Boolean.FALSE.equals(track.wanted) && reader.accepts(track);
            if (track.accepted) {
                requestTable(moov, track, reader);
                SampleSizes sizes = track.sampleSizes;
                if (sizes != null && sizes.present > maxSamples)
                    track.capped = true;
            }
            track.chunkOffsets = null;
            track.sampleSizes = null;
            track.chunkRuns = null;
            track.timeRuns = null;
        }
    }

    /** Asks for the samples the sample tables of {@code track} locate, in order. */
    private static void requestTable(@NotNull IsoBoxContext box, @NotNull Track track, @NotNull SampleReader reader)
    {
        long[] offsets = track.chunkOffsets;
        SampleSizes sizes = track.sampleSizes;
        long[] runs = track.chunkRuns;
        long[] times = track.timeRuns;
        if (offsets == null || sizes == null || runs == null)
            return;
        int entries = runs.length / 2;
        long nextChunk = 1;
        int timeRun = 0;
        long timeLeft = 0;
        long delta = 0;
        for (int entry = 0; entry < entries && track.samples < sizes.count; entry++) {
            long firstChunk = runs[entry * 2];
            long perChunk = runs[entry * 2 + 1];
            long nextFirst = entry + 1 < entries ? runs[(entry + 1) * 2] : offsets.length + 1L;
            for (long chunk = Math.max(firstChunk, nextChunk); chunk < nextFirst && chunk <= offsets.length && track.samples < sizes.count; chunk++) {
                nextChunk = chunk + 1;
                long offset = offsets[(int) (chunk - 1)];
                for (long i = 0; i < perChunk && track.samples < sizes.count; i++) {
                    while (timeLeft == 0 && times != null && timeRun < times.length / 2) {
                        timeLeft = times[timeRun * 2];
                        delta = times[timeRun * 2 + 1];
                        timeRun++;
                    }
                    long duration = timeLeft > 0 ? delta : 0;
                    if (timeLeft > 0)
                        timeLeft--;
                    long size = sizes.get(track.samples);
                    request(box, track, sample(box, track, duration, offset, size), reader);
                    track.time += duration;
                    offset += size;
                }
            }
        }
    }

    /** Where the data of the next track fragment of {@code moof} starts. */
    @NotNull
    private static Movie movie(@NotNull IsoBoxContext moof)
    {
        Movie movie = moof.get(Movie.class);
        if (movie == null) {
            movie = new Movie();
            movie.nextDataOffset = moof.box().offset;
            moof.put(Movie.class, movie);
        }
        return movie;
    }

    private static void readFragmentHeader(@NotNull IsoBoxContext box, @NotNull SequentialReader payload, int flags,
                                           @NotNull Tracks tracks) throws IOException
    {
        IsoBoxContext traf = box.parent();
        IsoBoxContext moof = box.ancestor("moof");
        if (moof == null)
            return;
        Movie movie = movie(moof);
        Fragment unlocated = new Fragment();
        traf.put(Fragment.class, unlocated);
        long fields = ((flags & 0x01) != 0 ? 8 : 0) + ((flags & 0x02) != 0 ? 4 : 0)
            + ((flags & 0x08) != 0 ? 4 : 0) + ((flags & 0x10) != 0 ? 4 : 0) + ((flags & 0x20) != 0 ? 4 : 0);
        if (remaining(box) < 4) {
            movie.located = false;
            throw new IOException("No track ID");
        }
        Track known = tracks.byId.get(payload.getUInt32());
        Track track = known != null && known.accepted && !known.lost ? known : null;
        if (remaining(box) < fields) {
            movie.located = false;
            if (track != null)
                track.lost = true;
            throw new IOException("Shorter than its flags declare");
        }
        Fragment fragment = new Fragment();
        if ((flags & 0x01) != 0) {
            fragment.base = payload.getInt64();
            fragment.located = true;
        } else if ((flags & 0x020000) != 0) {
            fragment.base = moof.box().offset;
            fragment.located = true;
        } else {
            fragment.base = movie.nextDataOffset;
            fragment.located = movie.located;
        }
        if ((flags & 0x02) != 0)
            payload.skip(4);
        fragment.defaultSampleDuration = (flags & 0x08) != 0 ? payload.getUInt32() : known == null ? 0 : known.defaultSampleDuration;
        fragment.defaultSampleSize = (flags & 0x10) != 0 ? payload.getUInt32() : known == null ? 0 : known.defaultSampleSize;
        fragment.next = fragment.base;
        movie.located = fragment.located;
        movie.nextDataOffset = fragment.base;
        if (!fragment.located && track != null) {
            box.addError("Fragment data of track " + track.id + " follows a damaged track fragment; skipped");
            track = null;
        }
        fragment.track = track;
        traf.put(Fragment.class, fragment);
    }

    private static void readTrackRun(@NotNull IsoBoxContext box, @NotNull SequentialReader payload, int flags,
                                     @NotNull SampleReader reader, int maxSamples) throws IOException
    {
        IsoBoxContext traf = box.parent();
        IsoBoxContext moof = box.ancestor("moof");
        if (traf == null || moof == null)
            return;
        Movie movie = movie(moof);
        Fragment fragment = traf.get(Fragment.class);
        if (fragment == null) {
            movie.located = false;
            traf.put(Fragment.class, new Fragment());
            traf.addError("Track fragment without a 'tfhd' before its runs; its data cannot be placed");
            return;
        }
        Track track = fragment.track;
        long count = payload.getUInt32();
        int width = 4 * Integer.bitCount(flags & 0xF00);
        long fields = ((flags & 0x01) != 0 ? 4 : 0) + ((flags & 0x04) != 0 ? 4 : 0);
        if (remaining(box) < fields || (width > 0 && (remaining(box) - fields) / width < count)) {
            movie.located = false;
            if (track != null)
                track.lost = true;
            throw new IOException("Shorter than its sample count declares");
        }
        long offset = (flags & 0x01) != 0 ? fragment.base + payload.getInt32() : fragment.next;
        if ((flags & 0x04) != 0)
            payload.skip(4);
        long i = 0;
        for (; i < count; i++) {
            if (width == 0 && (track == null || track.lost || track.samples >= maxSamples))
                break;
            long duration = (flags & 0x100) != 0 ? payload.getUInt32() : fragment.defaultSampleDuration;
            long size = (flags & 0x200) != 0 ? payload.getUInt32() : fragment.defaultSampleSize;
            if ((flags & 0x400) != 0)
                payload.skip(4);
            if ((flags & 0x800) != 0)
                payload.skip(4);
            if (track != null && !track.lost && track.samples < maxSamples) {
                request(box, track, sample(box, track, duration, offset, size), reader);
                track.time += duration;
            }
            offset += size;
        }
        if (i < count && track != null && !track.lost)
            track.capped = true;
        if (i < count) {
            long size = fragment.defaultSampleSize;
            long rest = count - i;
            offset = size > 0 && rest > (Long.MAX_VALUE - Math.max(offset, 0)) / size ? Long.MAX_VALUE : offset + rest * size;
        }
        fragment.next = offset;
        movie.nextDataOffset = offset;
        movie.located = fragment.located;
    }

    /** The track's next sample, at its current time, presented as its edit list says. */
    @NotNull
    private static Sample sample(@NotNull IsoBoxContext box, @NotNull Track track, long duration, long offset, long size)
    {
        Tracks tracks = box.root().get(Tracks.class);
        long movieTimescale = tracks == null ? 0 : tracks.movieTimescale;
        return new Sample(track.samples++, track.time, presentationTime(track, movieTimescale, track.time), duration, offset, size);
    }

    /**
     * The time at which a sample decoded at {@code time} is presented, in the track's time scale,
     * by the track's edit list: the sum of the edits before the one holding the sample, plus the
     * sample's distance into that edit. A segment duration of 0 runs to the end. Without an edit
     * list, {@code time}; -1 when no edit presents the sample.
     */
    static long presentationTime(@NotNull Track track, long movieTimescale, long time)
    {
        long[] edits = track.edits;
        if (edits == null)
            return time;
        long offset = 0;
        for (int i = 0; i < edits.length; i += 2) {
            long duration = toTrackTime(edits[i], track.timescale, movieTimescale);
            long mediaTime = edits[i + 1];
            if (mediaTime >= 0 && time >= mediaTime && (duration == 0 || time - mediaTime < duration))
                return offset + (time - mediaTime);
            if (duration == 0)
                break;
            offset += duration;
        }
        return -1;
    }

    /** {@code movieTime} in the track's time scale; unchanged when either scale is unknown. */
    private static long toTrackTime(long movieTime, long trackTimescale, long movieTimescale)
    {
        if (trackTimescale <= 0 || movieTimescale <= 0 || movieTime <= 0)
            return movieTime;
        if (movieTime > Long.MAX_VALUE / trackTimescale)
            return (long) ((double) movieTime * trackTimescale / movieTimescale);
        return movieTime * trackTimescale / movieTimescale;
    }

    private static void request(@NotNull IsoBoxContext box, @NotNull final Track track, @NotNull final Sample sample,
                                @NotNull final SampleReader reader)
    {
        box.requestRange(sample.getOffset(), sample.getSize(), new IsoBoxContext.RangeReader()
        {
            @Override
            public void read(@NotNull IsoBoxContext data, @NotNull SequentialReader bytes) throws IOException
            {
                if (!track.lost)
                    reader.readSample(data.root(), track, sample, bytes);
            }
        });
    }
}
