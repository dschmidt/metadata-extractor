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

import com.drew.metadata.mp4.Mp4Directory;

import java.util.HashMap;

public abstract class Mp4MediaDirectory extends Mp4Directory
{
    public static final int TAG_CREATION_TIME = 101;
    public static final int TAG_MODIFICATION_TIME = 102;
    public static final int TAG_DURATION = 103;
    public static final int TAG_LANGUAGE_CODE = 104;
    /**
     * The scheme type of a protected track's {@code sinf} ({@code cenc}, {@code cbcs}, Apple's
     * {@code drms}, and so on), or the protected sample entry's own type when it names no scheme.
     * The track's format tag then holds the original format from {@code frma}.
     */
    public static final int TAG_PROTECTION_SCHEME = 105;

    protected static void addMp4MediaTags(HashMap<Integer, String> map)
    {
        map.put(TAG_CREATION_TIME, "Creation Time");
        map.put(TAG_MODIFICATION_TIME, "Modification Time");
        map.put(TAG_DURATION, "Duration");
        map.put(TAG_LANGUAGE_CODE, "ISO 639-2 Language Code");
        map.put(TAG_PROTECTION_SCHEME, "Protection Scheme");
    }
}
