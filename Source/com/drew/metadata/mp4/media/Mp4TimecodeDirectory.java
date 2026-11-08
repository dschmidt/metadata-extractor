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
package com.drew.metadata.mp4.media;

import com.drew.lang.annotations.NotNull;

import java.util.HashMap;

/** A timecode track ({@code tmcd}): its sample description flags and its timecode media information. */
public class Mp4TimecodeDirectory extends Mp4MediaDirectory
{
    public static final int TAG_DROP_FRAME                              = 601;
    public static final int TAG_24_HOUR_MAX                             = 602;
    public static final int TAG_NEGATIVE_TIMES_OK                       = 603;
    public static final int TAG_COUNTER                                 = 604;
    public static final int TAG_TEXT_FONT                               = 605;
    public static final int TAG_TEXT_FACE                               = 606;
    public static final int TAG_TEXT_SIZE                               = 607;
    public static final int TAG_TEXT_COLOR                              = 608;
    public static final int TAG_BACKGROUND_COLOR                        = 609;
    public static final int TAG_FONT_NAME                               = 610;

    public Mp4TimecodeDirectory()
    {
        this.setDescriptor(new Mp4TimecodeDescriptor(this));
    }

    @NotNull
    private static final HashMap<Integer, String> _tagNameMap = new HashMap<Integer, String>();

    static
    {
        Mp4MediaDirectory.addMp4MediaTags(_tagNameMap);
        _tagNameMap.put(TAG_DROP_FRAME, "Drop Frame");
        _tagNameMap.put(TAG_24_HOUR_MAX, "24 Hour Max");
        _tagNameMap.put(TAG_NEGATIVE_TIMES_OK, "Negative Times OK");
        _tagNameMap.put(TAG_COUNTER, "Counter");
        _tagNameMap.put(TAG_TEXT_FONT, "Text Font");
        _tagNameMap.put(TAG_TEXT_FACE, "Text Face");
        _tagNameMap.put(TAG_TEXT_SIZE, "Text Size");
        _tagNameMap.put(TAG_TEXT_COLOR, "Text Color");
        _tagNameMap.put(TAG_BACKGROUND_COLOR, "Background Color");
        _tagNameMap.put(TAG_FONT_NAME, "Font Name");
    }

    @NotNull
    @Override
    public String getName()
    {
        return "MP4 Timecode";
    }

    @NotNull
    @Override
    protected HashMap<Integer, String> getTagNameMap()
    {
        return _tagNameMap;
    }
}
