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

import com.drew.imaging.isobmff.IsoBox;
import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxIterator;
import com.drew.imaging.isobmff.IsoSamples;
import com.drew.lang.Rational;
import com.drew.lang.SequentialByteArrayReader;
import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.metadata.mov.media.QuickTimeLivePhotoDirectory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the still image time of an Apple Live Photo's movie from its timed metadata track: a
 * {@code mebx} track whose sample entry names the key {@code com.apple.quicktime.still-image-time}
 * holds one sample, and the moment of the movie it is presented at, by the track's edit list, is
 * when the photo was taken. The same sample carries the transform from the video frame to the
 * photo and the dimensions it refers to. Fills one {@link QuickTimeLivePhotoDirectory} per file.
 */
final class LivePhotoSampleReader implements IsoSamples.SampleReader
{
    static final String KEY_STILL_IMAGE_TIME = "com.apple.quicktime.still-image-time";
    static final String KEY_TRANSFORM = "com.apple.quicktime.live-photo-still-image-transform";
    static final String KEY_REFERENCE_DIMENSIONS = "com.apple.quicktime.live-photo-still-image-transform-reference-dimensions";

    /** The fixed fields of a sample entry before its boxes: six reserved bytes and the data reference index. */
    private static final int SAMPLE_ENTRY_FIELDS = 8;

    @Override
    public boolean accepts(@NotNull IsoSamples.Track track)
    {
        return "meta".equals(track.getHandlerType()) && "mebx".equals(track.getFormat())
            && keys(track).containsValue(KEY_STILL_IMAGE_TIME);
    }

    @Override
    public void readSample(@NotNull IsoBoxContext file, @NotNull IsoSamples.Track track, @NotNull IsoSamples.Sample sample,
                           @NotNull SequentialReader data) throws IOException
    {
        if (file.get(QuickTimeLivePhotoDirectory.class) != null)
            return;
        Map<Integer, String> keys = keys(track);
        QuickTimeLivePhotoDirectory directory = new QuickTimeLivePhotoDirectory();
        long size = sample.getSize();
        while (size - data.getPosition() >= 8) {
            long entrySize = data.getUInt32();
            int key = data.getInt32();
            if (entrySize < 8 || entrySize - 8 > size - data.getPosition())
                break;
            byte[] value = data.getBytes((int) (entrySize - 8));
            String name = keys.get(key);
            if (KEY_STILL_IMAGE_TIME.equals(name)) {
                long time = sample.getPresentationTime() >= 0 ? sample.getPresentationTime() : sample.getTime();
                if (track.getTimescale() > 0)
                    directory.setRational(QuickTimeLivePhotoDirectory.TAG_STILL_IMAGE_TIME, new Rational(time, track.getTimescale()));
            } else if (KEY_TRANSFORM.equals(name) && value.length == 9 * 8) {
                SequentialReader reader = new SequentialByteArrayReader(value);
                double[] transform = new double[9];
                for (int i = 0; i < 9; i++)
                    transform[i] = reader.getDouble64();
                directory.setDoubleArray(QuickTimeLivePhotoDirectory.TAG_STILL_IMAGE_TRANSFORM, transform);
            } else if (KEY_REFERENCE_DIMENSIONS.equals(name) && value.length == 2 * 4) {
                // two floats holding whole pixel counts
                SequentialReader reader = new SequentialByteArrayReader(value);
                directory.setIntArray(QuickTimeLivePhotoDirectory.TAG_STILL_IMAGE_REFERENCE_DIMENSIONS,
                    new int[] {Math.round(reader.getFloat32()), Math.round(reader.getFloat32())});
            }
        }
        if (directory.getTagCount() == 0)
            return;
        file.metadata().addDirectory(directory);
        file.put(QuickTimeLivePhotoDirectory.class, directory);
    }

    /**
     * The keys of a {@code mebx} sample entry, by the local key ID the samples use: each box of
     * its {@code keys} is named by the ID and holds a {@code keyd} with the namespace and the key.
     */
    @NotNull
    static Map<Integer, String> keys(@NotNull IsoSamples.Track track)
    {
        Map<Integer, String> keys = new HashMap<Integer, String>();
        byte[] entry = track.getSampleEntry();
        if (entry == null || entry.length <= SAMPLE_ENTRY_FIELDS)
            return keys;
        try {
            IsoBoxIterator boxes = new IsoBoxIterator(entry, SAMPLE_ENTRY_FIELDS, entry.length);
            for (IsoBox box = boxes.next(); box != null; box = boxes.next()) {
                if (!box.type.equals("keys"))
                    continue;
                IsoBoxIterator ids = boxes.children();
                for (IsoBox id = ids.next(); id != null; id = ids.next()) {
                    IsoBoxIterator definitions = ids.children();
                    for (IsoBox definition = definitions.next(); definition != null; definition = definitions.next()) {
                        if (!definition.type.equals("keyd"))
                            continue;
                        byte[] bytes = definitions.payloadBytes();
                        if (bytes.length > 4)
                            keys.put(id.typeCode, new String(bytes, 4, bytes.length - 4, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            // a malformed entry names no keys
        }
        return keys;
    }
}
