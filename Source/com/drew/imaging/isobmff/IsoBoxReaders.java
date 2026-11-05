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

import com.drew.lang.SequentialByteArrayReader;
import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Walks a box sequence and calls the {@link IsoBoxReader}s registered for each box's path.
 * Build it once and reuse it: it holds no per-file state.
 * <p>
 * A path names box types from the top level down, separated by {@code /}, such as
 * {@code moov/trak/mdia/hdlr}. {@code *} matches any one box, and {@code uuid:} followed by a
 * usertype, such as {@code uuid:85c0b687-820f-11e0-8111-f4ce462b6a48}, matches a {@code uuid}
 * box with that usertype; a plain {@code uuid} matches every {@code uuid} box. The walk
 * descends only into containers on the way to a registered path and skips everything else
 * unread.
 * <p>
 * Readers registered for the same box run in the order they were passed, after the layout.
 * A box with one reader and nothing registered below it is streamed to that reader. A box with
 * several readers, or with a reader and registered paths below it, is read into memory once
 * (up to {@code maxBufferedPayload}) so each reader and the descent start from the beginning of
 * the payload.
 * <p>
 * A problem in one box is reported to the file context's error handler and the walk goes on
 * with the next box. When the input ends inside a box, that is reported once, as a
 * {@link TruncatedInputException}, and the walk stops.
 */
public final class IsoBoxReaders
{
    /** Reads one box; the functional form of {@link IsoBoxReader}. */
    public interface Callback
    {
        void read(@NotNull IsoBoxContext box) throws IOException;
    }

    /** A reader that calls {@code callback} for the boxes at {@code paths}. */
    @NotNull
    public static IsoBoxReader on(@NotNull final Callback callback, @NotNull final String... paths)
    {
        return new IsoBoxReader()
        {
            @NotNull
            @Override
            public Iterable<String> getBoxPaths()
            {
                return Arrays.asList(paths);
            }

            @Override
            public void readBox(@NotNull IsoBoxContext box) throws IOException
            {
                callback.read(box);
            }
        };
    }

    /** The default of {@code maxBufferedPayload}: 64 MiB. */
    public static final long DEFAULT_MAX_BUFFERED_PAYLOAD = 64L * 1024 * 1024;

    @NotNull
    private final IsoBoxLayout _layout;
    private final long _maxBufferedPayload;
    private final Node _root = new Node();
    /** Every reader, in the order the readers of one box run: the layout's first, then as passed. */
    private final List<IsoBoxReader> _order = new ArrayList<IsoBoxReader>();
    private static final List<Node> NOWHERE = Collections.singletonList(new Node());

    /** A walk with {@link #DEFAULT_MAX_BUFFERED_PAYLOAD}. */
    public IsoBoxReaders(@NotNull IsoBoxLayout layout, @NotNull Iterable<? extends IsoBoxReader> readers)
    {
        this(layout, readers, DEFAULT_MAX_BUFFERED_PAYLOAD);
    }

    /**
     * @param maxBufferedPayload the largest payload read into memory for several readers of one
     *                           box
     *                           or for ranges that overlap
     */
    public IsoBoxReaders(@NotNull IsoBoxLayout layout, @NotNull Iterable<? extends IsoBoxReader> readers, long maxBufferedPayload)
    {
        _layout = layout;
        _maxBufferedPayload = maxBufferedPayload;
        for (IsoBoxReader reader : readers)
            register(reader);
        if (layout instanceof IsoBoxReader)
            registerLayout((IsoBoxReader) layout);
    }

    private void register(IsoBoxReader reader)
    {
        if (!_order.contains(reader))
            _order.add(reader);
        for (String path : reader.getBoxPaths()) {
            Node node = _root;
            for (String segment : path.split("/"))
                node = node.child(segment);
            if (!node.readers.contains(reader))
                node.readers.add(reader);
        }
    }

    /**
     * Reads {@code inputStream}, starting from the file context {@code root} (see
     * {@link IsoBoxContext#root}).
     * Ranges asked for that could not be read are reported as errors; to read them in a second
     * pass over a marked stream, see {@link #read(InputStream, IsoBoxContext, int)}.
     */
    public void read(@NotNull InputStream inputStream, @NotNull IsoBoxContext root)
    {
        read(new IsoBoxIterator(inputStream), root);
        reportPendingRanges(root);
    }

    /**
     * Reads {@code inputStream} like {@link #read(InputStream, IsoBoxContext)}. Data that comes
     * before the boxes that locate it, such as HEIF items in an {@code mdat} before
     * {@code meta}, needs a second pass: if {@code rereadLimit} is positive and the stream
     * supports {@link InputStream#mark}, the stream is marked with that limit and read again for
     * that data only. Data that still could not be read is reported as an error.
     */
    public void read(@NotNull InputStream inputStream, @NotNull IsoBoxContext root, int rereadLimit)
    {
        boolean marked = rereadLimit > 0 && inputStream.markSupported();
        if (marked)
            inputStream.mark(rereadLimit);
        read(new IsoBoxIterator(inputStream), root);
        if (marked && hasPendingRanges(root)) {
            try {
                inputStream.reset();
                readRanges(new IsoBoxIterator(inputStream), root);
            } catch (IOException e) {
                root.addError("Unable to read the input a second time: " + e.getMessage());
            }
        }
        reportPendingRanges(root);
    }

    /**
     * Reads {@code boxes}, starting from the file context {@code root} (see
     * {@link IsoBoxContext#root}).
     * Ranges asked for that came before the boxes that located them stay pending (see
     * {@link #hasPendingRanges}): follow with {@link #readRanges} over a second pass, or with
     * {@link #reportPendingRanges}.
     */
    public void read(@NotNull IsoBoxIterator boxes, @NotNull IsoBoxContext root)
    {
        root._nodes = _root.self;
        root._readers = this;
        new Walk().walk(boxes, root, _root.self);
        root.done();
    }

    /**
     * Reads {@code boxes}, a second pass over the same input, for the pending ranges only, then
     * reports those still not served.
     */
    public void readRanges(@NotNull IsoBoxIterator boxes, @NotNull IsoBoxContext root)
    {
        root._readers = this;
        new Walk().walk(boxes, root, NOWHERE);
        reportPendingRanges(root);
    }

    /** Whether ranges asked for are still unserved. */
    public static boolean hasPendingRanges(@NotNull IsoBoxContext root)
    {
        return root._ranges != null && !root._ranges.isEmpty();
    }

    /** Reports the ranges still unserved as one error and forgets them. */
    public static void reportPendingRanges(@NotNull IsoBoxContext root)
    {
        if (!hasPendingRanges(root))
            return;
        List<Ranges.Range> ranges = root._ranges.takeAll();
        long bytes = 0;
        for (Ranges.Range range : ranges)
            bytes += range.length;
        root.addError(ranges.size() + (ranges.size() == 1 ? " range" : " ranges") + " of data (" + bytes + " bytes, from input offset "
            + ranges.get(0).offset + ") not read: it comes before the boxes that locate it, or lies outside any box");
    }

    /** Walks {@code boxes} as the children of {@code parent}, with the readers registered below it. */
    @SuppressWarnings("unchecked")
    void walkBoxes(@NotNull IsoBoxIterator boxes, @NotNull IsoBoxContext parent)
    {
        new Walk().walk(boxes, parent, parent._nodes == null ? NOWHERE : (List<Node>) parent._nodes);
    }

    private final class Walk
    {
        private boolean _ended;

        void walk(IsoBoxIterator boxes, IsoBoxContext parent, List<Node> nodes)
        {
            try {
                for (IsoBox box = boxes.next(); box != null; box = boxes.next()) {
                    if (box.extendsToEnd && box.size != -1 && parent != parent.root()) {
                        String container = parent.box().type;
                        new IsoBoxContext(parent, box).addError("Box '" + box.type + "' declares size 0 inside '"
                            + container + "'; read to the end of '" + container + "'");
                    }
                    List<Node> next = step(nodes, box);
                    Ranges ranges = parent.root()._ranges;
                    if (next.isEmpty() && ranges != null && !ranges.isEmpty()) {
                        long end = box.payloadSize == -1 ? Long.MAX_VALUE : box.payloadOffset + box.payloadSize;
                        if (ranges.within(box.payloadOffset, end))
                            serve(boxes, new IsoBoxContext(parent, box), ranges.take(box.payloadOffset, end));
                        if (_ended || parent.root()._stopped)
                            return;
                        continue;
                    }
                    if (next.isEmpty())
                        continue;
                    IsoBoxContext context = new IsoBoxContext(parent, box);
                    context._nodes = next;
                    try {
                        boolean descend = false;
                        for (Node node : next)
                            descend |= node.hasChildren();
                        read(boxes, context, next, readers(next), descend);
                    } catch (IOException e) {
                        report(context, "Error reading box '" + box.type + "': " + e.getMessage(), e);
                    }
                    context.done();
                    if (_ended)
                        return;
                    if (parent.root()._stopped)
                        return;
                }
            } catch (IOException e) {
                report(parent, String.valueOf(e.getMessage()), e);
            }
        }

        /**
         * Hands the ranges inside {@code context}'s payload to their readers, in order. Ranges
         * that overlap are read into memory together, and each reader gets a view of its range.
         */
        private void serve(IsoBoxIterator boxes, IsoBoxContext context, List<Ranges.Range> ranges)
        {
            IsoBox box = context.box();
            try {
                context.stream(boxes);
                SequentialReader payload = context.payload();
                for (int first = 0; first < ranges.size(); ) {
                    long start = ranges.get(first).offset;
                    long end = start + ranges.get(first).length;
                    int last = first + 1;
                    while (last < ranges.size() && ranges.get(last).offset < end) {
                        end = Math.max(end, ranges.get(last).offset + ranges.get(last).length);
                        last++;
                    }
                    List<Ranges.Range> group = ranges.subList(first, last);
                    first = last;
                    long position = start - box.payloadOffset;
                    if (position < payload.getPosition()) {
                        context.addError("Range at input offset " + start + " lies before the data already read; skipped");
                        continue;
                    }
                    payload.skip(position - payload.getPosition());
                    if (group.size() == 1) {
                        serve(context, group.get(0), context.slice(group.get(0).length));
                    } else if (end - start > _maxBufferedPayload) {
                        context.addError("Overlapping ranges at input offset " + start + " (" + (end - start)
                            + " bytes) are too large to read into memory; skipped");
                    } else {
                        byte[] bytes = payload.getBytes((int) (end - start));
                        for (Ranges.Range range : group) {
                            int from = (int) (range.offset - start);
                            serve(context, range, new BoundedSequentialReader(new SequentialByteArrayReader(bytes, from), from, from + range.length, box));
                        }
                    }
                    if (_ended || context.root()._stopped)
                        return;
                    long left = end - box.payloadOffset - payload.getPosition();
                    if (left > 0)
                        payload.skip(left);
                }
            } catch (IOException e) {
                report(context, "Error reading box '" + box.type + "': " + e.getMessage(), e);
            } finally {
                context.release();
            }
        }

        private void serve(IsoBoxContext context, Ranges.Range range, SequentialReader data)
        {
            try {
                range.reader.read(context, data);
            } catch (IOException e) {
                report(context, "Error reading range at input offset " + range.offset + ": " + e.getMessage(), e);
            }
        }

        private void report(IsoBoxContext context, String message, IOException e)
        {
            if (_ended)
                return;
            if (e instanceof TruncatedInputException)
                _ended = true;
            context.addError(message, e);
        }

        private void read(IsoBoxIterator boxes, IsoBoxContext context, List<Node> path,
                          List<IsoBoxReader> readers, boolean descend) throws IOException
        {
            IsoBox box = context.box();
            if (readers.size() == 1 && !descend) {
                context.stream(boxes);
                readers.get(0).readBox(context);
                context.release();
                return;
            }
            if (readers.isEmpty()) {
                context.stream(boxes);
                IsoBoxIterator children = _layout.enterContainer(context) ? context.children() : null;
                context.release();
                if (children != null)
                    walk(children, context, path);
                return;
            }
            if (box.payloadSize == -1 || box.payloadSize > _maxBufferedPayload) {
                context.addError("Box '" + box.type + "' payload (" + box.payloadSize
                    + " bytes) is too large to read for several readers; skipped");
                return;
            }
            byte[] bytes = boxes.payloadBytes();
            for (IsoBoxReader reader : readers) {
                context.buffer(bytes);
                try {
                    reader.readBox(context);
                } catch (IOException e) {
                    report(context, "Error reading box '" + box.type + "': " + e.getMessage(), e);
                }
            }
            if (descend) {
                context.buffer(bytes);
                IsoBoxIterator children = _layout.enterContainer(context) ? context.children() : null;
                context.release();
                if (children != null)
                    walk(children, context, path);
            }
            context.release();
        }
    }

    private static List<Node> step(List<Node> nodes, IsoBox box)
    {
        if (nodes.size() == 1 && box.usertype == null) {
            Node node = nodes.get(0);
            Node exact = node.find(box.typeCode);
            if (exact == null)
                return node.wildcard == null ? Collections.<Node>emptyList() : node.wildcard.self;
            if (node.wildcard == null)
                return exact.self;
        }
        List<Node> next = new ArrayList<Node>(2);
        for (Node node : nodes) {
            Node exact = node.find(box.typeCode);
            if (exact != null)
                next.add(exact);
            if (box.usertype != null) {
                for (int i = 0; i < node.usertypes.size(); i++) {
                    if (Arrays.equals(node.usertypes.get(i), box.usertype))
                        next.add(node.uuidChildren.get(i));
                }
            }
            if (node.wildcard != null)
                next.add(node.wildcard);
        }
        return next;
    }

    /**
     * Registers the layout's paths that lie in containers the walk enters for the other
     * readers, ahead of those readers.
     */
    private void registerLayout(IsoBoxReader layout)
    {
        _order.add(0, layout);
        for (String path : layout.getBoxPaths()) {
            String[] segments = path.split("/");
            Node node = _root;
            for (int i = 0; i < segments.length - 1 && node != null; i++)
                node = node.existing(segments[i]);
            if (node == null)
                continue;
            Node last = node.child(segments[segments.length - 1]);
            if (!last.readers.contains(layout))
                last.readers.add(0, layout);
        }
    }

    /** The readers of the boxes at {@code nodes}, in {@link #_order}. */
    private List<IsoBoxReader> readers(List<Node> nodes)
    {
        if (nodes.size() == 1)
            return nodes.get(0).readers;
        List<IsoBoxReader> readers = new ArrayList<IsoBoxReader>();
        for (IsoBoxReader reader : _order) {
            for (Node node : nodes) {
                if (node.readers.contains(reader)) {
                    readers.add(reader);
                    break;
                }
            }
        }
        return readers;
    }

    /** One step of the registered paths: the readers registered there and where the paths continue. */
    private static final class Node
    {
        final List<IsoBoxReader> readers = new ArrayList<IsoBoxReader>(1);
        final List<Node> self = Collections.singletonList(this);
        private int[] _keys = new int[0];
        private Node[] _children = new Node[0];
        final List<byte[]> usertypes = new ArrayList<byte[]>(0);
        final List<Node> uuidChildren = new ArrayList<Node>(0);
        @Nullable
        Node wildcard;

        Node child(String segment)
        {
            if (segment.equals("*")) {
                if (wildcard == null)
                    wildcard = new Node();
                return wildcard;
            }
            if (segment.startsWith("uuid:")) {
                byte[] usertype = usertype(segment.substring(5));
                for (int i = 0; i < usertypes.size(); i++) {
                    if (Arrays.equals(usertypes.get(i), usertype))
                        return uuidChildren.get(i);
                }
                Node node = new Node();
                usertypes.add(usertype);
                uuidChildren.add(node);
                return node;
            }
            int type = typeCode(segment);
            Node node = find(type);
            if (node == null) {
                node = new Node();
                _keys = Arrays.copyOf(_keys, _keys.length + 1);
                _children = Arrays.copyOf(_children, _children.length + 1);
                _keys[_keys.length - 1] = type;
                _children[_children.length - 1] = node;
            }
            return node;
        }

        /** The node for {@code segment} if a path was registered through it, else null. */
        @Nullable
        Node existing(String segment)
        {
            if (segment.equals("*"))
                return wildcard;
            if (segment.startsWith("uuid:")) {
                byte[] usertype = usertype(segment.substring(5));
                for (int i = 0; i < usertypes.size(); i++) {
                    if (Arrays.equals(usertypes.get(i), usertype))
                        return uuidChildren.get(i);
                }
                return null;
            }
            return find(typeCode(segment));
        }

        @Nullable
        Node find(int type)
        {
            int[] keys = _keys;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] == type)
                    return _children[i];
            }
            return null;
        }

        boolean hasChildren()
        {
            return _keys.length > 0 || !uuidChildren.isEmpty() || wildcard != null;
        }

        private static int typeCode(String type)
        {
            if (type.length() != 4)
                throw new IllegalArgumentException("Box type must have four characters: " + type);
            int code = 0;
            for (int i = 0; i < 4; i++) {
                char c = type.charAt(i);
                if (c > 0xFF)
                    throw new IllegalArgumentException("Box type must be ISO-8859-1: " + type);
                code = (code << 8) | c;
            }
            return code;
        }

        private static byte[] usertype(String uuid)
        {
            String hex = uuid.replace("-", "");
            if (hex.length() != 32)
                throw new IllegalArgumentException("Invalid usertype: " + uuid);
            byte[] bytes = new byte[16];
            for (int i = 0; i < 16; i++)
                bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            return bytes;
        }
    }
}
