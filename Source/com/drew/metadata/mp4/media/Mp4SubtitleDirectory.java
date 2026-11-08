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

/** A subtitle track ({@code sbtl}): its QuickTime subtitle sample description. */
public class Mp4SubtitleDirectory extends Mp4MediaDirectory
{
    public static final int TAG_VERTICAL_PLACEMENT                      = 501;
    public static final int TAG_SOME_SAMPLES_FORCED                     = 502;
    public static final int TAG_ALL_SAMPLES_FORCED                      = 503;
    public static final int TAG_DEFAULT_TEXT_BOX                        = 504;
    public static final int TAG_FONT_IDENTIFIER                         = 505;
    public static final int TAG_FONT_FACE                               = 506;
    public static final int TAG_FONT_SIZE                               = 507;
    public static final int TAG_FOREGROUND_COLOR                        = 508;

    public Mp4SubtitleDirectory()
    {
        this.setDescriptor(new Mp4SubtitleDescriptor(this));
    }

    @NotNull
    private static final HashMap<Integer, String> _tagNameMap = new HashMap<Integer, String>();

    static
    {
        Mp4MediaDirectory.addMp4MediaTags(_tagNameMap);
        _tagNameMap.put(TAG_VERTICAL_PLACEMENT, "Vertical Placement");
        _tagNameMap.put(TAG_SOME_SAMPLES_FORCED, "Some Samples Forced");
        _tagNameMap.put(TAG_ALL_SAMPLES_FORCED, "All Samples Forced");
        _tagNameMap.put(TAG_DEFAULT_TEXT_BOX, "Default Text Box");
        _tagNameMap.put(TAG_FONT_IDENTIFIER, "Font Identifier");
        _tagNameMap.put(TAG_FONT_FACE, "Font Face");
        _tagNameMap.put(TAG_FONT_SIZE, "Font Size");
        _tagNameMap.put(TAG_FOREGROUND_COLOR, "Foreground Color");
    }

    @NotNull
    @Override
    public String getName()
    {
        return "MP4 Subtitle";
    }

    @NotNull
    @Override
    protected HashMap<Integer, String> getTagNameMap()
    {
        return _tagNameMap;
    }
}
