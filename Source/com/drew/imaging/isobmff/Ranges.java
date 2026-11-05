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
 *    https://drewnoakes.com/code/exif/
 *    https://github.com/drewnoakes/metadata-extractor
 */
package com.drew.imaging.isobmff;

import com.drew.lang.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The ranges of the input readers asked for and the walk has not served yet, by offset. */
final class Ranges
{
    static final class Range
    {
        final long offset;
        final long length;
        @NotNull
        final IsoBoxContext.RangeReader reader;

        Range(long offset, long length, @NotNull IsoBoxContext.RangeReader reader)
        {
            this.offset = offset;
            this.length = length;
            this.reader = reader;
        }
    }

    private final TreeMap<Long, List<Range>> _pending = new TreeMap<Long, List<Range>>();

    void add(@NotNull Range range)
    {
        List<Range> ranges = _pending.get(range.offset);
        if (ranges == null) {
            ranges = new ArrayList<Range>(1);
            _pending.put(range.offset, ranges);
        }
        ranges.add(range);
    }

    boolean isEmpty()
    {
        return _pending.isEmpty();
    }

    /** Whether a pending range starts in {@code [start, end)}. */
    boolean within(long start, long end)
    {
        Long key = _pending.ceilingKey(start);
        return key != null && key < end;
    }

    /** Removes and returns the pending ranges that start in {@code [start, end)}, by offset. */
    @NotNull
    List<Range> take(long start, long end)
    {
        List<Range> taken = new ArrayList<Range>();
        Iterator<Map.Entry<Long, List<Range>>> entries = _pending.subMap(start, end).entrySet().iterator();
        while (entries.hasNext()) {
            taken.addAll(entries.next().getValue());
            entries.remove();
        }
        return taken;
    }

    /** Removes and returns all pending ranges. */
    @NotNull
    List<Range> takeAll()
    {
        return take(Long.MIN_VALUE, Long.MAX_VALUE);
    }
}
