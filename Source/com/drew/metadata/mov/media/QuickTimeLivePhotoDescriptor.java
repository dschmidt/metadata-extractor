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
package com.drew.metadata.mov.media;

import com.drew.lang.Rational;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.mov.QuickTimeDescriptor;

import java.util.Locale;

import static com.drew.metadata.mov.media.QuickTimeLivePhotoDirectory.*;

public class QuickTimeLivePhotoDescriptor extends QuickTimeDescriptor
{
    public QuickTimeLivePhotoDescriptor(@NotNull QuickTimeLivePhotoDirectory directory)
    {
        super(directory);
    }

    @Override
    public String getDescription(int tagType)
    {
        switch (tagType) {
            case TAG_STILL_IMAGE_TIME:
                return getStillImageTimeDescription();
            case TAG_STILL_IMAGE_REFERENCE_DIMENSIONS:
                return getReferenceDimensionsDescription();
            default:
                return super.getDescription(tagType);
        }
    }

    @Nullable
    private String getStillImageTimeDescription()
    {
        Rational time = _directory.getRational(TAG_STILL_IMAGE_TIME);
        return time == null ? null : String.format(Locale.ROOT, "%.3f seconds", time.doubleValue());
    }

    @Nullable
    private String getReferenceDimensionsDescription()
    {
        int[] dimensions = _directory.getIntArray(TAG_STILL_IMAGE_REFERENCE_DIMENSIONS);
        if (dimensions == null || dimensions.length != 2)
            return null;
        return dimensions[0] + " x " + dimensions[1];
    }
}
