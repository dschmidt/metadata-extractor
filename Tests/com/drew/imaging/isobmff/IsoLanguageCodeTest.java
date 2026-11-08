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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class IsoLanguageCodeTest
{
    private static int packed(String code)
    {
        return ((code.charAt(0) - 0x60) << 10) | ((code.charAt(1) - 0x60) << 5) | (code.charAt(2) - 0x60);
    }

    @Test
    public void testPackedLettersAndMacintoshCodes()
    {
        assertEquals("eng", IsoLanguageCode.decode(packed("eng")));
        assertEquals("und", IsoLanguageCode.decode(0x55C4));
        assertEquals("eng", IsoLanguageCode.decode(0));
        assertEquals("spa", IsoLanguageCode.decode(6));
        assertEquals("cym", IsoLanguageCode.decode(128));
        assertEquals("aze", IsoLanguageCode.decode(150));
        assertNull(IsoLanguageCode.decode(95));
        assertNull(IsoLanguageCode.decode(151));
        assertNull(IsoLanguageCode.decode(0x7FFF));
        assertEquals("und", IsoLanguageCode.decode((21 << 10) | (14 << 5) | 4));
        assertNull(IsoLanguageCode.decode((27 << 10) | (14 << 5) | 4)); // a letter outside a to z
        assertNull(IsoLanguageCode.decode(-1));
    }
}
