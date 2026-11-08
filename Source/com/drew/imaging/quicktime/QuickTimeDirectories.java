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
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeMediaDirectory;
import com.drew.metadata.mov.media.QuickTimeMusicDirectory;
import com.drew.metadata.mov.media.QuickTimeSoundDirectory;
import com.drew.metadata.mov.media.QuickTimeSubtitleDirectory;
import com.drew.metadata.mov.media.QuickTimeTextDirectory;
import com.drew.metadata.mov.media.QuickTimeTimecodeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4MediaDirectory;
import com.drew.metadata.mp4.media.Mp4MusicDirectory;
import com.drew.metadata.mp4.media.Mp4SoundDirectory;
import com.drew.metadata.mp4.media.Mp4SubtitleDirectory;
import com.drew.metadata.mp4.media.Mp4TextDirectory;
import com.drew.metadata.mp4.media.Mp4TimecodeDirectory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;

import java.io.IOException;

/**
 * Turns what the MP4 readers read from a QuickTime file into the deprecated directories of
 * {@code com.drew.metadata.mov}: the tags have the same names in both, so each value is copied
 * under the old tag number. Directories the old reader never produced (hint and timed metadata
 * tracks, {@code uuid} boxes, the Live Photo) are left out, and the one value the old reader
 * computed differently, the video frame rate, is recomputed its way by
 * {@link #FIRST_ENTRY_FRAME_RATE}.
 */
@SuppressWarnings("deprecation")
final class QuickTimeDirectories
{
    private QuickTimeDirectories() {}

    /** Pairs of an MP4 tag and the QuickTime tag it is copied to. */
    private static final int[] FILE_TAGS = {
        Mp4Directory.TAG_CREATION_TIME, QuickTimeDirectory.TAG_CREATION_TIME,
        Mp4Directory.TAG_MODIFICATION_TIME, QuickTimeDirectory.TAG_MODIFICATION_TIME,
        Mp4Directory.TAG_TIME_SCALE, QuickTimeDirectory.TAG_TIME_SCALE,
        Mp4Directory.TAG_DURATION, QuickTimeDirectory.TAG_DURATION,
        Mp4Directory.TAG_DURATION_SECONDS, QuickTimeDirectory.TAG_DURATION_SECONDS,
        Mp4Directory.TAG_PREFERRED_RATE, QuickTimeDirectory.TAG_PREFERRED_RATE,
        Mp4Directory.TAG_PREFERRED_VOLUME, QuickTimeDirectory.TAG_PREFERRED_VOLUME,
        Mp4Directory.TAG_PREVIEW_TIME, QuickTimeDirectory.TAG_PREVIEW_TIME,
        Mp4Directory.TAG_PREVIEW_DURATION, QuickTimeDirectory.TAG_PREVIEW_DURATION,
        Mp4Directory.TAG_POSTER_TIME, QuickTimeDirectory.TAG_POSTER_TIME,
        Mp4Directory.TAG_SELECTION_TIME, QuickTimeDirectory.TAG_SELECTION_TIME,
        Mp4Directory.TAG_SELECTION_DURATION, QuickTimeDirectory.TAG_SELECTION_DURATION,
        Mp4Directory.TAG_CURRENT_TIME, QuickTimeDirectory.TAG_CURRENT_TIME,
        Mp4Directory.TAG_NEXT_TRACK_ID, QuickTimeDirectory.TAG_NEXT_TRACK_ID,
        Mp4Directory.TAG_ROTATION, QuickTimeDirectory.TAG_ROTATION,
        Mp4Directory.TAG_MEDIA_TIME_SCALE, QuickTimeDirectory.TAG_MEDIA_TIME_SCALE,
        Mp4Directory.TAG_MAJOR_BRAND, QuickTimeDirectory.TAG_MAJOR_BRAND,
        Mp4Directory.TAG_MINOR_VERSION, QuickTimeDirectory.TAG_MINOR_VERSION,
        Mp4Directory.TAG_COMPATIBLE_BRANDS, QuickTimeDirectory.TAG_COMPATIBLE_BRANDS,
        Mp4Directory.TAG_CANON_THUMBNAIL_DATE_TIME, QuickTimeDirectory.TAG_CANON_THUMBNAIL_DT,
    };

    private static final int[] MEDIA_TAGS = {
        Mp4MediaDirectory.TAG_CREATION_TIME, QuickTimeMediaDirectory.TAG_CREATION_TIME,
        Mp4MediaDirectory.TAG_MODIFICATION_TIME, QuickTimeMediaDirectory.TAG_MODIFICATION_TIME,
        Mp4MediaDirectory.TAG_DURATION, QuickTimeMediaDirectory.TAG_DURATION,
    };

    private static final int[] VIDEO_TAGS = {
        Mp4VideoDirectory.TAG_VENDOR, QuickTimeVideoDirectory.TAG_VENDOR,
        Mp4VideoDirectory.TAG_TEMPORAL_QUALITY, QuickTimeVideoDirectory.TAG_TEMPORAL_QUALITY,
        Mp4VideoDirectory.TAG_SPATIAL_QUALITY, QuickTimeVideoDirectory.TAG_SPATIAL_QUALITY,
        Mp4VideoDirectory.TAG_WIDTH, QuickTimeVideoDirectory.TAG_WIDTH,
        Mp4VideoDirectory.TAG_HEIGHT, QuickTimeVideoDirectory.TAG_HEIGHT,
        Mp4VideoDirectory.TAG_HORIZONTAL_RESOLUTION, QuickTimeVideoDirectory.TAG_HORIZONTAL_RESOLUTION,
        Mp4VideoDirectory.TAG_VERTICAL_RESOLUTION, QuickTimeVideoDirectory.TAG_VERTICAL_RESOLUTION,
        Mp4VideoDirectory.TAG_COMPRESSOR_NAME, QuickTimeVideoDirectory.TAG_COMPRESSOR_NAME,
        Mp4VideoDirectory.TAG_DEPTH, QuickTimeVideoDirectory.TAG_DEPTH,
        Mp4VideoDirectory.TAG_COMPRESSION_TYPE, QuickTimeVideoDirectory.TAG_COMPRESSION_TYPE,
        Mp4VideoDirectory.TAG_GRAPHICS_MODE, QuickTimeVideoDirectory.TAG_GRAPHICS_MODE,
        Mp4VideoDirectory.TAG_OPCOLOR, QuickTimeVideoDirectory.TAG_OPCOLOR,
        Mp4VideoDirectory.TAG_COLOR_TABLE, QuickTimeVideoDirectory.TAG_COLOR_TABLE,
        Mp4VideoDirectory.TAG_FRAME_RATE, QuickTimeVideoDirectory.TAG_FRAME_RATE,
    };

    private static final int[] SOUND_TAGS = {
        Mp4SoundDirectory.TAG_AUDIO_FORMAT, QuickTimeSoundDirectory.TAG_AUDIO_FORMAT,
        Mp4SoundDirectory.TAG_NUMBER_OF_CHANNELS, QuickTimeSoundDirectory.TAG_NUMBER_OF_CHANNELS,
        Mp4SoundDirectory.TAG_AUDIO_SAMPLE_SIZE, QuickTimeSoundDirectory.TAG_AUDIO_SAMPLE_SIZE,
        Mp4SoundDirectory.TAG_AUDIO_SAMPLE_RATE, QuickTimeSoundDirectory.TAG_AUDIO_SAMPLE_RATE,
        Mp4SoundDirectory.TAG_SOUND_BALANCE, QuickTimeSoundDirectory.TAG_SOUND_BALANCE,
    };

    private static final int[] TIMECODE_TAGS = {
        Mp4TimecodeDirectory.TAG_DROP_FRAME, QuickTimeTimecodeDirectory.TAG_DROP_FRAME,
        Mp4TimecodeDirectory.TAG_24_HOUR_MAX, QuickTimeTimecodeDirectory.TAG_24_HOUR_MAX,
        Mp4TimecodeDirectory.TAG_NEGATIVE_TIMES_OK, QuickTimeTimecodeDirectory.TAG_NEGATIVE_TIMES_OK,
        Mp4TimecodeDirectory.TAG_COUNTER, QuickTimeTimecodeDirectory.TAG_COUNTER,
        Mp4TimecodeDirectory.TAG_TEXT_FONT, QuickTimeTimecodeDirectory.TAG_TEXT_FONT,
        Mp4TimecodeDirectory.TAG_TEXT_FACE, QuickTimeTimecodeDirectory.TAG_TEXT_FACE,
        Mp4TimecodeDirectory.TAG_TEXT_SIZE, QuickTimeTimecodeDirectory.TAG_TEXT_SIZE,
        Mp4TimecodeDirectory.TAG_TEXT_COLOR, QuickTimeTimecodeDirectory.TAG_TEXT_COLOR,
        Mp4TimecodeDirectory.TAG_BACKGROUND_COLOR, QuickTimeTimecodeDirectory.TAG_BACKGROUND_COLOR,
        Mp4TimecodeDirectory.TAG_FONT_NAME, QuickTimeTimecodeDirectory.TAG_FONT_NAME,
    };

    private static final int[] TEXT_TAGS = {
        Mp4TextDirectory.TAG_AUTO_SCALE, QuickTimeTextDirectory.TAG_AUTO_SCALE,
        Mp4TextDirectory.TAG_MOVIE_BACKGROUND_COLOR, QuickTimeTextDirectory.TAG_MOVIE_BACKGROUND_COLOR,
        Mp4TextDirectory.TAG_SCROLL_IN, QuickTimeTextDirectory.TAG_SCROLL_IN,
        Mp4TextDirectory.TAG_SCROLL_OUT, QuickTimeTextDirectory.TAG_SCROLL_OUT,
        Mp4TextDirectory.TAG_HORIZONTAL_SCROLL, QuickTimeTextDirectory.TAG_HORIZONTAL_SCROLL,
        Mp4TextDirectory.TAG_REVERSE_SCROLL, QuickTimeTextDirectory.TAG_REVERSE_SCROLL,
        Mp4TextDirectory.TAG_CONTINUOUS_SCROLL, QuickTimeTextDirectory.TAG_CONTINUOUS_SCROLL,
        Mp4TextDirectory.TAG_DROP_SHADOW, QuickTimeTextDirectory.TAG_DROP_SHADOW,
        Mp4TextDirectory.TAG_ANTI_ALIAS, QuickTimeTextDirectory.TAG_ANTI_ALIAS,
        Mp4TextDirectory.TAG_KEY_TEXT, QuickTimeTextDirectory.TAG_KEY_TEXT,
        Mp4TextDirectory.TAG_JUSTIFICATION, QuickTimeTextDirectory.TAG_JUSTIFICATION,
        Mp4TextDirectory.TAG_BACKGROUND_COLOR, QuickTimeTextDirectory.TAG_BACKGROUND_COLOR,
        Mp4TextDirectory.TAG_DEFAULT_TEXT_BOX, QuickTimeTextDirectory.TAG_DEFAULT_TEXT_BOX,
        Mp4TextDirectory.TAG_FONT_NUMBER, QuickTimeTextDirectory.TAG_FONT_NUMBER,
        Mp4TextDirectory.TAG_FONT_FACE, QuickTimeTextDirectory.TAG_FONT_FACE,
        Mp4TextDirectory.TAG_FOREGROUND_COLOR, QuickTimeTextDirectory.TAG_FOREGROUND_COLOR,
        Mp4TextDirectory.TAG_NAME, QuickTimeTextDirectory.TAG_NAME,
    };

    private static final int[] SUBTITLE_TAGS = {
        Mp4SubtitleDirectory.TAG_VERTICAL_PLACEMENT, QuickTimeSubtitleDirectory.TAG_VERTICAL_PLACEMENT,
        Mp4SubtitleDirectory.TAG_SOME_SAMPLES_FORCED, QuickTimeSubtitleDirectory.TAG_SOME_SAMPLES_FORCED,
        Mp4SubtitleDirectory.TAG_ALL_SAMPLES_FORCED, QuickTimeSubtitleDirectory.TAG_ALL_SAMPLES_FORCED,
        Mp4SubtitleDirectory.TAG_DEFAULT_TEXT_BOX, QuickTimeSubtitleDirectory.TAG_DEFAULT_TEXT_BOX,
        Mp4SubtitleDirectory.TAG_FONT_IDENTIFIER, QuickTimeSubtitleDirectory.TAG_FONT_IDENTIFIER,
        Mp4SubtitleDirectory.TAG_FONT_FACE, QuickTimeSubtitleDirectory.TAG_FONT_FACE,
        Mp4SubtitleDirectory.TAG_FONT_SIZE, QuickTimeSubtitleDirectory.TAG_FONT_SIZE,
        Mp4SubtitleDirectory.TAG_FOREGROUND_COLOR, QuickTimeSubtitleDirectory.TAG_FOREGROUND_COLOR,
    };

    /** The time scale of a track's {@code mdhd}, for {@link #FIRST_ENTRY_FRAME_RATE}. */
    private static final class TimeScale
    {
        final long value;

        TimeScale(long value)
        {
            this.value = value;
        }
    }

    /**
     * The video frame rate as the QuickTime reader always computed it: the media time scale over
     * the duration of the first {@code stts} entry, where {@link com.drew.imaging.mp4.Mp4MetadataReader}
     * averages over the whole track. It overwrites the average once the track's {@code mdia} is read.
     */
    static final IsoBoxReader FIRST_ENTRY_FRAME_RATE = IsoBoxReaders.on(new IsoBoxReaders.Callback()
    {
        @Override
        public void read(@NotNull IsoBoxContext box) throws IOException
        {
            SequentialReader payload = box.payload();
            if (box.box().type.equals("mdhd")) {
                int version = payload.getUInt8();
                payload.skip(version == 1 ? 19 : 11);
                box.parent().put(TimeScale.class, new TimeScale(payload.getUInt32()));
                return;
            }
            payload.skip(4);
            if (payload.getUInt32() == 0)
                return;
            payload.skip(4);
            final long duration = payload.getUInt32();
            IsoBoxContext mdia = box.ancestor("mdia");
            if (mdia == null)
                return;
            mdia.whenDone(new IsoBoxReaders.Callback()
            {
                @Override
                public void read(@NotNull IsoBoxContext done)
                {
                    TimeScale timeScale = done.get(TimeScale.class);
                    Mp4MediaDirectory media = done.get(Mp4MediaDirectory.class);
                    if (timeScale != null && media instanceof Mp4VideoDirectory)
                        media.setFloat(Mp4VideoDirectory.TAG_FRAME_RATE, (float) timeScale.value / (float) duration);
                }
            });
        }
    }, "moov/trak/mdia/mdhd", "moov/trak/mdia/minf/stbl/stts");

    /**
     * Moves what was read into {@code read} over to {@code metadata}: the {@link Mp4Directory}'s
     * tags into {@code file}, each media directory into its QuickTime counterpart, and every other
     * directory as it is, with {@code file} as its parent where the {@link Mp4Directory} was.
     */
    static void convert(@NotNull Metadata read, @NotNull Metadata metadata, @NotNull QuickTimeDirectory file)
    {
        for (Directory directory : read.getDirectories()) {
            if (directory.getClass() == Mp4Directory.class) {
                copy(directory, file, FILE_TAGS);
            } else if (directory instanceof Mp4MediaDirectory) {
                QuickTimeDirectory media = mediaDirectory((Mp4MediaDirectory) directory);
                if (media == null)
                    continue;
                copy(directory, media, MEDIA_TAGS);
                metadata.addDirectory(media);
            } else if (!(directory instanceof Mp4Directory)) {
                if (directory.getParent() instanceof Mp4Directory)
                    directory.setParent(file);
                metadata.addDirectory(directory);
            }
        }
    }

    /** The QuickTime directory for {@code media}, with the tags of its kind copied, or {@code null} for kinds the QuickTime reader had none for. */
    @Nullable
    private static QuickTimeDirectory mediaDirectory(@NotNull Mp4MediaDirectory media)
    {
        if (media instanceof Mp4VideoDirectory)
            return copy(media, new QuickTimeVideoDirectory(), VIDEO_TAGS);
        if (media instanceof Mp4SoundDirectory)
            return copy(media, new QuickTimeSoundDirectory(), SOUND_TAGS);
        if (media instanceof Mp4TimecodeDirectory)
            return copy(media, new QuickTimeTimecodeDirectory(), TIMECODE_TAGS);
        if (media instanceof Mp4TextDirectory)
            return copy(media, new QuickTimeTextDirectory(), TEXT_TAGS);
        if (media instanceof Mp4SubtitleDirectory)
            return copy(media, new QuickTimeSubtitleDirectory(), SUBTITLE_TAGS);
        if (media instanceof Mp4MusicDirectory)
            return new QuickTimeMusicDirectory();
        return null;
    }

    /** Copies the values of the first tag of each pair in {@code tags} to the second, and the errors. */
    @NotNull
    private static <T extends Directory> T copy(@NotNull Directory from, @NotNull T to, @NotNull int[] tags)
    {
        for (int i = 0; i < tags.length; i += 2) {
            Object value = from.getObject(tags[i]);
            if (value != null)
                to.setObject(tags[i + 1], value);
        }
        for (String error : from.getErrors())
            to.addError(error);
        return to;
    }
}
