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

/** A text track: the QuickTime text sample description, if the track has one. */
public class Mp4TextDirectory extends Mp4MediaDirectory
{
    public static final int TAG_AUTO_SCALE                              = 401;
    public static final int TAG_MOVIE_BACKGROUND_COLOR                  = 402;
    public static final int TAG_SCROLL_IN                               = 403;
    public static final int TAG_SCROLL_OUT                              = 404;
    public static final int TAG_HORIZONTAL_SCROLL                       = 405;
    public static final int TAG_REVERSE_SCROLL                          = 406;
    public static final int TAG_CONTINUOUS_SCROLL                       = 407;
    public static final int TAG_DROP_SHADOW                             = 408;
    public static final int TAG_ANTI_ALIAS                              = 409;
    public static final int TAG_KEY_TEXT                                = 410;
    public static final int TAG_JUSTIFICATION                           = 411;
    public static final int TAG_BACKGROUND_COLOR                        = 412;
    public static final int TAG_DEFAULT_TEXT_BOX                        = 413;
    public static final int TAG_FONT_NUMBER                             = 414;
    public static final int TAG_FONT_FACE                               = 415;
    public static final int TAG_FOREGROUND_COLOR                        = 416;
    public static final int TAG_NAME                                    = 417;

    public Mp4TextDirectory()
    {
        this.setDescriptor(new Mp4TextDescriptor(this));
    }

    @NotNull
    private static final HashMap<Integer, String> _tagNameMap = new HashMap<Integer, String>();

    static
    {
        Mp4MediaDirectory.addMp4MediaTags(_tagNameMap);
        _tagNameMap.put(TAG_AUTO_SCALE, "Auto Scale");
        _tagNameMap.put(TAG_MOVIE_BACKGROUND_COLOR, "Use Background Color");
        _tagNameMap.put(TAG_SCROLL_IN, "Scroll In");
        _tagNameMap.put(TAG_SCROLL_OUT, "Scroll Out");
        _tagNameMap.put(TAG_HORIZONTAL_SCROLL, "Scroll Orientation");
        _tagNameMap.put(TAG_REVERSE_SCROLL, "Scroll Direction");
        _tagNameMap.put(TAG_CONTINUOUS_SCROLL, "Continuous Scroll");
        _tagNameMap.put(TAG_DROP_SHADOW, "Drop Shadow");
        _tagNameMap.put(TAG_ANTI_ALIAS, "Anti-aliasing");
        _tagNameMap.put(TAG_KEY_TEXT, "Display Text Background Color");
        _tagNameMap.put(TAG_JUSTIFICATION, "Alignment");
        _tagNameMap.put(TAG_BACKGROUND_COLOR, "Background Color");
        _tagNameMap.put(TAG_DEFAULT_TEXT_BOX, "Default Text Box");
        _tagNameMap.put(TAG_FONT_NUMBER, "Font Number");
        _tagNameMap.put(TAG_FONT_FACE, "Font Face");
        _tagNameMap.put(TAG_FOREGROUND_COLOR, "Foreground Color");
        _tagNameMap.put(TAG_NAME, "Font Name");
    }

    @NotNull
    @Override
    public String getName()
    {
        return "MP4 Text";
    }

    @NotNull
    @Override
    protected HashMap<Integer, String> getTagNameMap()
    {
        return _tagNameMap;
    }
}
