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

import com.drew.lang.annotations.Nullable;

/**
 * The language field of a media header ({@code mdhd}) and of QuickTime's text atoms, as an
 * ISO 639-2/T code. ISO base media files pack three letters into 15 bits, five each, offset by
 * {@code 0x60} ({@code 0x55C4} is {@code und}); QuickTime files may hold a Macintosh language
 * code instead, a number below {@code 0x400} where 0 is English.
 */
public final class IsoLanguageCode
{
    private IsoLanguageCode() {}

    /** The Macintosh language codes 0 to 150, as the QuickTime File Format specification lists them. */
    private static final String[] MACINTOSH = {
        "eng", "fra", "deu", "ita", "nld", "swe", "spa", "dan", "por", "nor",
        "heb", "jpn", "ara", "fin", "ell", "isl", "mlt", "tur", "hrv", "zho",
        "urd", "hin", "tha", "kor", "lit", "pol", "hun", "est", "lav", "smi",
        "fao", "fas", "rus", "zho", "nld", "gle", "sqi", "ron", "ces", "slk",
        "slv", "yid", "srp", "mkd", "bul", "ukr", "bel", "uzb", "kaz", "aze",
        "aze", "hye", "kat", "ron", "kir", "tgk", "tuk", "mon", "mon", "pus",
        "kur", "kas", "snd", "bod", "nep", "san", "mar", "ben", "asm", "guj",
        "pan", "ori", "mal", "kan", "tam", "tel", "sin", "mya", "khm", "lao",
        "vie", "ind", "tgl", "msa", "msa", "amh", "tir", "orm", "som", "swa",
        "kin", "run", "nya", "mlg", "epo", null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, "cym", "eus",
        "cat", "lat", "que", "grn", "aym", "tat", "uig", "dzo", "jav", "sun",
        "glg", "afr", "bre", "iku", "gla", "glv", "gle", "ton", "grc", "kal",
        "aze"
    };

    /**
     * The ISO 639-2/T code for a language field, or {@code null} when it names no language:
     * an unassigned Macintosh code, {@code 0x7FFF} (unspecified), or packed letters outside
     * {@code a} to {@code z}. {@code und} is returned as it is.
     */
    @Nullable
    public static String decode(int packed)
    {
        if (packed < 0)
            return null;
        if (packed < 0x400)
            return packed < MACINTOSH.length ? MACINTOSH[packed] : null;
        if (packed == 0x7FFF)
            return null;
        char[] letters = new char[3];
        for (int i = 0; i < 3; i++) {
            int letter = (packed >> (10 - 5 * i)) & 0x1F;
            if (letter < 1 || letter > 26)
                return null;
            letters[i] = (char) (letter + 0x60);
        }
        return new String(letters);
    }
}
