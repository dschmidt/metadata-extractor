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
import java.io.RandomAccessFile;
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
 * Data that boxes locate by input position rather than by path, such as HEIF items or track
 * samples, is asked for with {@link IsoBoxContext#requestRange} and read on the way through the
 * box it lies in, usually {@code mdat}. When that box comes before the box that locates the data,
 * the walk has passed it by the time the range is asked for. Reading a file or a random access
 * reader (see {@link IsoInput}) serves such ranges by position at the end. Reading a stream, the
 * walk keeps the top-level boxes it would
 * skip, up to {@code maxBufferedPayload} each, until it has read every box the readers name in
 * {@link IsoBoxReader#getLocatingPaths}; with the usual order, {@code meta} or {@code moov} before
 * {@code mdat}, nothing is kept. Ranges that could not be read either way are reported.
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

    /** The most bytes of a payload a reader may read into memory at once; see {@link IsoBoxContext#payloadBytes}. */
    long maxBufferedPayload()
    {
        return _maxBufferedPayload;
    }
    private final Node _root = new Node();
    /** Every reader, in the order the readers of one box run: the layout's first, then as passed. */
    private final List<IsoBoxReader> _order = new ArrayList<IsoBoxReader>();
    /** How many boxes the readers name in {@link IsoBoxReader#getLocatingPaths}. */
    private int _locatingCount;
    private static final List<Node> NOWHERE = Collections.singletonList(new Node());

    /** A walk with {@link #DEFAULT_MAX_BUFFERED_PAYLOAD}. */
    public IsoBoxReaders(@NotNull IsoBoxLayout layout, @NotNull Iterable<? extends IsoBoxReader> readers)
    {
        this(layout, readers, DEFAULT_MAX_BUFFERED_PAYLOAD);
    }

    /**
     * @param maxBufferedPayload the largest payload read into memory for several readers of one
     *                           box, for ranges that overlap, or kept for ranges asked for later
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
        for (String path : reader.getLocatingPaths()) {
            Node node = _root;
            for (String segment : path.split("/"))
                node = node.child(segment);
            if (node.locating < 0)
                node.locating = _locatingCount++;
        }
    }

    /**
     * Reads {@code input}, starting from the file context {@code root} (see
     * {@link IsoBoxContext#root}). A stream is read once from the start: ranges asked for after
     * the walk passed their box are served from the boxes it kept (see
     * {@link IsoBoxReader#getLocatingPaths}). A file or a random access reader is read by
     * position for such ranges at the end instead, so nothing is kept. Ranges that could not be
     * read either way are reported as errors.
     *
     * @throws IOException if a file cannot be opened or closed; problems in the data are reported
     *                     to the file context's error handler, not thrown
     */
    public void read(@NotNull IsoInput input, @NotNull IsoBoxContext root) throws IOException
    {
        if (input.stream != null) {
            read(input.stream, root);
        } else if (input.file != null) {
            RandomAccessFile file = new RandomAccessFile(input.file, "r");
            try {
                read(new IsoInput.SeekableFile(file), root);
            } finally {
                file.close();
            }
        } else {
            read(new IsoInput.SeekableReader(input.reader), root);
        }
    }

    /** Reads a stream; see {@link #read(IsoInput, IsoBoxContext)}. */
    public void read(@NotNull InputStream inputStream, @NotNull IsoBoxContext root)
    {
        read(new IsoBoxIterator(inputStream), root);
    }

    /**
     * Reads {@code boxes} like a stream (see {@link #read(IsoInput, IsoBoxContext)}), starting from
     * the file context {@code root} (see {@link IsoBoxContext#root}).
     */
    public void read(@NotNull IsoBoxIterator boxes, @NotNull IsoBoxContext root)
    {
        walk(boxes, root);
        reportPendingRanges(root);
    }

    /** Reads {@code input} once from the start, then the pending ranges by position. */
    void read(@NotNull IsoInput.Seekable input, @NotNull IsoBoxContext root) throws IOException
    {
        root._seekable = true;
        try {
            walk(new IsoBoxIterator(new SeekingSequentialReader(input), input.length()), root);
            if (!root._stopped && hasPendingRanges(root))
                seekRanges(input, root);
        } finally {
            root._seekable = false;
        }
        reportPendingRanges(root);
    }

    /** Reads the pending ranges from {@code input} by position, in order. */
    private static void seekRanges(IsoInput.Seekable input, IsoBoxContext root) throws IOException
    {
        long length = input.length();
        for (Ranges.Range range : root._ranges.takeAll()) {
            if (range.offset < 0 || range.length < 0 || range.length > length || range.offset > length - range.length) {
                root.addError("Range at input offset " + range.offset + " (" + range.length + " bytes) lies outside the input; skipped");
                continue;
            }
            if (range.length > Integer.MAX_VALUE) {
                root.addError("Range at input offset " + range.offset + " (" + range.length + " bytes) is too large to read; skipped");
                continue;
            }
            try {
                byte[] bytes = new byte[(int) range.length];
                input.read(range.offset, bytes, 0, bytes.length);
                range.reader.read(root, new SequentialByteArrayReader(bytes));
            } catch (IOException e) {
                root.addError("Error reading range at input offset " + range.offset + ": " + e.getMessage(), e);
            }
        }
    }

    /** Walks {@code boxes} from the top level; ranges not served stay pending on {@code root}. */
    private void walk(@NotNull IsoBoxIterator boxes, @NotNull IsoBoxContext root)
    {
        root._nodes = _root.self;
        root._readers = this;
        root._locatingLeft = _locatingCount;
        root._locatingSeen = _locatingCount == 0 ? null : new boolean[_locatingCount];
        try {
            new Walk().walk(boxes, root, _root.self);
            root.done();
        } finally {
            root.releaseRetained();
        }
    }

    /** Whether ranges asked for are still unserved. */
    private static boolean hasPendingRanges(@NotNull IsoBoxContext root)
    {
        return root._ranges != null && !root._ranges.isEmpty();
    }

    /** Reports the ranges still unserved as one error and forgets them. */
    private static void reportPendingRanges(@NotNull IsoBoxContext root)
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
                    if (next.isEmpty()) {
                        skip(boxes, parent, box);
                        if (_ended || parent.root()._stopped)
                            return;
                        continue;
                    }
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
                    located(parent.root(), next);
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

        /** Counts the locating boxes among {@code nodes} that the walk reads for the first time. */
        private void located(IsoBoxContext root, List<Node> nodes)
        {
            if (root._locatingLeft == 0)
                return;
            for (Node node : nodes) {
                if (node.locating >= 0 && !root._locatingSeen[node.locating]) {
                    root._locatingSeen[node.locating] = true;
                    root._locatingLeft--;
                }
            }
        }

        /**
         * Passes a box no reader is registered for: serves the pending ranges in it, and keeps
         * it (see {@link IsoBoxReader#getLocatingPaths}) while a locating box is still to come.
         */
        private void skip(IsoBoxIterator boxes, IsoBoxContext parent, IsoBox box) throws IOException
        {
            IsoBoxContext root = parent.root();
            Ranges ranges = root._ranges;
            long end = box.payloadSize == -1 ? Long.MAX_VALUE : box.payloadOffset + box.payloadSize;
            boolean wanted = ranges != null && ranges.within(box.payloadOffset, end);
            if (keeps(root, parent, box)) {
                IsoBoxContext context = new IsoBoxContext(parent, box);
                IsoBoxContext.Retained kept;
                try {
                    kept = new IsoBoxContext.Retained(context, boxes.payloadBytes());
                } catch (IOException e) {
                    report(context, "Error reading box '" + box.type + "': " + e.getMessage(), e);
                    return;
                }
                root.retain(kept);
                if (wanted) {
                    for (Ranges.Range range : ranges.take(box.payloadOffset, end)) {
                        if (kept.covers(range.offset, range.length))
                            kept.serve(range);
                        else
                            context.addError("Range at input offset " + range.offset + " (" + range.length + " bytes) runs past the end of '" + box.type + "'; skipped");
                        if (root._stopped)
                            return;
                    }
                }
            } else if (wanted) {
                serve(boxes, new IsoBoxContext(parent, box), ranges.take(box.payloadOffset, end));
            }
        }

        /**
         * Whether to keep {@code box}: a top-level box of known size within the buffer limit, while
         * a locating box is still to come and the input cannot be read by position. {@code free}
         * and {@code skip} carry nothing by definition.
         */
        private boolean keeps(IsoBoxContext root, IsoBoxContext parent, IsoBox box)
        {
            return root._locatingLeft > 0 && !root._seekable && parent == root
                && box.payloadSize > 0 && box.payloadSize <= _maxBufferedPayload
                && !box.type.equals("free") && !box.type.equals("skip");
        }

        /**
         * Hands the ranges inside {@code context}'s payload to their readers, in order. Ranges
         * that overlap are read into memory together, and each reader gets a view of its range.
         */
        private void serve(IsoBoxIterator boxes, IsoBoxContext context, List<Ranges.Range> ranges)
        {
            IsoBox box = context.box();
            try {
                context.streamUnbuffered(boxes);
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
        /** This box's index among the locating boxes (see {@link IsoBoxReader#getLocatingPaths}), or -1. */
        int locating = -1;

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
