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
package com.drew.metadata.mp4;

import com.drew.lang.annotations.NotNull;


import java.util.HashMap;

/**
 * The still image of an Apple Live Photo's movie: the moment of the movie the photo was taken
 * at, from the timed metadata track whose key is {@code com.apple.quicktime.still-image-time},
 * and how the photo's frame maps onto the video's.
 */
public class LivePhotoDirectory extends Mp4Directory
{
    /** The time of the still image in the movie, in seconds, as a rational. */
    public static final int TAG_STILL_IMAGE_TIME = 1;
    /** The 3x3 transform from the video frame to the still image, nine doubles in row order. */
    public static final int TAG_STILL_IMAGE_TRANSFORM = 2;
    /** The width and height the transform refers to, as two integers. */
    public static final int TAG_STILL_IMAGE_REFERENCE_DIMENSIONS = 3;

    @NotNull
    private static final HashMap<Integer, String> _tagNameMap = new HashMap<Integer, String>();

    static
    {
        _tagNameMap.put(TAG_STILL_IMAGE_TIME, "Still Image Time");
        _tagNameMap.put(TAG_STILL_IMAGE_TRANSFORM, "Still Image Transform");
        _tagNameMap.put(TAG_STILL_IMAGE_REFERENCE_DIMENSIONS, "Still Image Reference Dimensions");
    }

    public LivePhotoDirectory()
    {
        this.setDescriptor(new LivePhotoDescriptor(this));
    }

    @Override
    @NotNull
    public String getName()
    {
        return "Live Photo";
    }

    @Override
    @NotNull
    protected HashMap<Integer, String> getTagNameMap()
    {
        return _tagNameMap;
    }
}
