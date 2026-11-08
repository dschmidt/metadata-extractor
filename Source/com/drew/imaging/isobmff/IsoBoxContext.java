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
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One box during a walk by {@link IsoBoxReaders}, or the file itself at the root.
 * <p>
 * Each context holds state keyed by class, visible to the box and everything inside it. A
 * reader stores what later boxes need in the container it belongs to: a {@code hdlr} reader
 * puts the handler type into {@code parent()}, so the rest of that {@code mdia} sees it, and
 * per-file state goes into {@link #root()}.
 */
public final class IsoBoxContext
{
    @Nullable
    private final IsoBoxContext _parent;
    @NotNull
    private final IsoBoxContext _root;
    @Nullable
    private final IsoBox _box;
    @Nullable
    private String _path;
    @NotNull
    private final Metadata _metadata;
    @NotNull
    private final ErrorHandler _errors;
    @Nullable
    private Object[] _values;
    @Nullable
    private SequentialReader _payload;
    @Nullable
    private IsoBoxIterator _iterator;
    @Nullable
    private byte[] _bytes;
    /** Where this box is in the registered paths; set by the walk. */
    @Nullable
    Object _nodes;
    /** The readers of the walk in progress, on the root only. */
    @Nullable
    IsoBoxReaders _readers;
    /** Whether a reader ended the walk, on the root only. */
    boolean _stopped;
    /** The ranges asked for and not served yet, on the root only. */
    @Nullable
    Ranges _ranges;
    /** Whether the walk can read ranges by position at the end, on the root only. */
    boolean _seekable;
    /** The boxes kept for ranges (see {@link IsoBoxReader#getLocatingPaths}), on the root only. */
    @Nullable
    private List<Retained> _retained;
    /** How many problems were reported for the file, on the root only; see {@link #MAX_ERRORS}. */
    private int _errorsReported;
    /** How many of the walk's locating boxes have not been read yet, on the root only. */
    int _locatingLeft;
    /** Which of the walk's locating boxes have been read, by index, on the root only. */
    @Nullable
    boolean[] _locatingSeen;
    @Nullable
    private List<IsoBoxReaders.Callback> _whenDone;

    /** Receives the problems of a walk. */
    public interface ErrorHandler
    {
        /**
         * @param box     the box the problem is in, or {@code null} for the input as a whole, so a
         *                caller can word the problem its own way
         * @param message the problem, naming the box
         * @param cause   the exception behind the problem, or {@code null} for a reader's own message
         */
        void error(@Nullable IsoBox box, @NotNull String message, @Nullable IOException cause);
    }

    /** The context of a file, where per-file state can be stored before a walk. */
    @NotNull
    public static IsoBoxContext root(@NotNull Metadata metadata, @NotNull final Directory errors)
    {
        return root(metadata, new ErrorHandler()
        {
            @Override
            public void error(@Nullable IsoBox box, @NotNull String message, @Nullable IOException cause)
            {
                errors.addError(message);
            }
        });
    }

    /** The context of a file, with problems sent to {@code errors}. */
    @NotNull
    public static IsoBoxContext root(@NotNull Metadata metadata, @NotNull ErrorHandler errors)
    {
        return new IsoBoxContext(metadata, errors);
    }

    private IsoBoxContext(@NotNull Metadata metadata, @NotNull ErrorHandler errors)
    {
        _parent = null;
        _root = this;
        _box = null;
        _path = "";
        _metadata = metadata;
        _errors = errors;
    }

    IsoBoxContext(@NotNull IsoBoxContext parent, @NotNull IsoBox box)
    {
        _parent = parent;
        _root = parent._root;
        _box = box;
        _metadata = parent._metadata;
        _errors = parent._errors;
    }

    /** The box; throws at the root. */
    @NotNull
    public IsoBox box()
    {
        if (_box == null)
            throw new IllegalStateException("The root has no box");
        return _box;
    }

    /** The types from the top level down to this box, separated by {@code /}; for tests. */
    @NotNull
    String path()
    {
        if (_path == null)
            _path = _parent._parent == null ? _box.type : _parent.path() + "/" + _box.type;
        return _path;
    }

    @Nullable
    public IsoBoxContext parent()
    {
        return _parent;
    }

    @NotNull
    public IsoBoxContext root()
    {
        return _root;
    }

    /** The nearest enclosing box of {@code type}, including this one, or {@code null}. */
    @Nullable
    public IsoBoxContext ancestor(@NotNull String type)
    {
        for (IsoBoxContext context = this; context != null; context = context._parent) {
            if (context._box != null && type.equals(context._box.type))
                return context;
        }
        return null;
    }

    @NotNull
    public Metadata metadata()
    {
        return _metadata;
    }

    /**
     * How many problems a walk reports per file; after that, one notice that the rest are
     * dropped, so a file with a problem in every box does not report every box.
     */
    public static final int MAX_ERRORS = 100;

    public void addError(@NotNull String message)
    {
        report(message, null);
    }

    void addError(@NotNull String message, @NotNull IOException cause)
    {
        report(message, cause);
    }

    private void report(@NotNull String message, @Nullable IOException cause)
    {
        int reported = ++_root._errorsReported;
        if (reported <= MAX_ERRORS)
            _errors.error(_box, message, cause);
        else if (reported == MAX_ERRORS + 1)
            _errors.error(null, "More than " + MAX_ERRORS + " problems in this file; the rest are not reported", null);
    }

    /**
     * The payload, from its start, for the reader being called. Positions start at 0 at the
     * beginning of the payload, whether it is streamed or was read into memory;
     * {@link IsoBox#payloadOffset} says where that is in the input. A payload of up to 16 KiB
     * is read in one piece on the first call; a reader that does not call it reads nothing.
     */
    @NotNull
    public SequentialReader payload() throws IOException
    {
        if (_payload == null) {
            if (_iterator == null)
                throw new IllegalStateException("No payload outside readBox or enterContainer");
            _payload = _iterator.directPayload();
        }
        return _payload;
    }

    /**
     * A reader over the next {@code length} bytes of the payload, such as a record's image data,
     * with positions starting at 0. Reading it advances the payload; reads past its end fail.
     */
    @NotNull
    public SequentialReader slice(long length) throws IOException
    {
        SequentialReader payload = payload();
        long start = payload.getPosition();
        return new BoundedSequentialReader(payload, start, start + length, box());
    }

    /**
     * The boxes inside the payload, from wherever reading has got to, for a reader that walks
     * them itself (to take only the first child, for example). Their offsets are input offsets.
     */
    @NotNull
    public IsoBoxIterator children() throws IOException
    {
        if (_iterator != null)
            return _iterator.children();
        if (_bytes != null) {
            int depth = depth() + 1;
            if (depth > IsoBoxIterator.MAX_DEPTH)
                throw new IOException("Maximum box nesting depth exceeded");
            long offset = box().payloadOffset;
            return new IsoBoxIterator(new OffsetByteArrayReader(_bytes, (int) payload().getPosition(), offset), offset + _bytes.length, depth);
        }
        throw new IllegalStateException("No payload outside readBox or enterContainer");
    }

    /** The number of boxes this box is nested in: 0 for a box at the top level. */
    private int depth()
    {
        int depth = -1;
        for (IsoBoxContext context = this; context._parent != null; context = context._parent)
            depth++;
        return depth;
    }

    /**
     * Calls {@code callback} with this context once the walk has passed this box and everything
     * in it, for a reader that needs several of its children together, such as the sample tables
     * of a track. On the file context it runs at the end of the walk.
     */
    public void whenDone(@NotNull IsoBoxReaders.Callback callback)
    {
        if (_whenDone == null)
            _whenDone = new ArrayList<IsoBoxReaders.Callback>(1);
        _whenDone.add(callback);
    }

    /** Runs and forgets the callbacks of {@link #whenDone}. */
    void done()
    {
        List<IsoBoxReaders.Callback> callbacks = _whenDone;
        if (callbacks == null)
            return;
        _whenDone = null;
        for (IsoBoxReaders.Callback callback : callbacks) {
            try {
                callback.read(this);
            } catch (IOException e) {
                addError("Error after reading box '" + (_box == null ? "" : _box.type) + "': " + e.getMessage());
            }
        }
    }

    /** Reads a range of the input that a reader asked for with {@link #requestRange}. */
    public interface RangeReader
    {
        /**
         * @param box  the box the range lies in, typically {@code mdat}, or the file context when
         *             the range was read by position rather than on the way through a box
         * @param data a reader limited to the range; whatever is not read is skipped
         */
        void read(@NotNull IsoBoxContext box, @NotNull SequentialReader data) throws IOException;
    }

    /**
     * Asks for {@code length} bytes at input position {@code offset}, for data that structure
     * boxes locate rather than a path, such as HEIF items or track samples. The walk hands them
     * to {@code reader} when it passes that position inside a box no reader is registered for,
     * skipping everything else. A range in a box the walk has kept (see
     * {@link IsoBoxReader#getLocatingPaths}) is read at once. A range the walk has already passed
     * is read by position at the end when the input allows that (see {@link IsoInput}), and is
     * reported as not read otherwise.
     */
    public void requestRange(long offset, long length, @NotNull RangeReader reader)
    {
        Ranges.Range range = new Ranges.Range(offset, length, reader);
        Retained kept = _root.retained(offset, length);
        if (kept != null) {
            kept.serve(range);
            return;
        }
        if (_root._ranges == null)
            _root._ranges = new Ranges();
        _root._ranges.add(range);
    }

    /** A top-level box the walk kept in memory for ranges asked for after passing it. */
    static final class Retained
    {
        @NotNull
        final IsoBoxContext context;
        final long offset;
        @NotNull
        final byte[] bytes;

        Retained(@NotNull IsoBoxContext context, @NotNull byte[] bytes)
        {
            this.context = context;
            this.offset = context.box().payloadOffset;
            this.bytes = bytes;
        }

        boolean covers(long start, long length)
        {
            return start >= offset && length <= bytes.length && start - offset <= bytes.length - length;
        }

        void serve(@NotNull Ranges.Range range)
        {
            int from = (int) (range.offset - offset);
            SequentialReader data = new BoundedSequentialReader(new SequentialByteArrayReader(bytes, from), from, from + range.length, context.box());
            try {
                range.reader.read(context, data);
            } catch (IOException e) {
                context.addError("Error reading range at input offset " + range.offset + ": " + e.getMessage(), e);
            }
        }
    }

    /** Keeps {@code kept} for the rest of the walk; on the file context only. */
    void retain(@NotNull Retained kept)
    {
        if (_retained == null)
            _retained = new ArrayList<Retained>(1);
        _retained.add(kept);
    }

    /** The kept box holding all of the range, if any; on the file context only. */
    @Nullable
    Retained retained(long offset, long length)
    {
        if (_retained == null)
            return null;
        for (Retained kept : _retained) {
            if (kept.covers(offset, length))
                return kept;
        }
        return null;
    }

    /** Forgets the kept boxes; on the file context only. */
    void releaseRetained()
    {
        _retained = null;
    }

    /**
     * Walks {@code boxes}, complete boxes such as a decompressed payload, as if they were the
     * children of this box: readers registered below this box's path are called for them. For
     * QuickTime's compressed {@code cmov}, a reader calls it on {@link #root()} with the
     * decompressed {@code moov}. Box offsets in {@code boxes} are indexes into the array.
     */
    public void walkBoxes(@NotNull byte[] boxes) throws IOException
    {
        IsoBoxReaders readers = _root._readers;
        if (readers == null)
            throw new IllegalStateException("No walk in progress");
        int depth = depth() + 1;
        if (depth > IsoBoxIterator.MAX_DEPTH)
            throw new IOException("Maximum box nesting depth exceeded");
        readers.walkBoxes(new IsoBoxIterator(new SequentialByteArrayReader(boxes), boxes.length, depth), this);
    }

    /** Ends the walk after the current box: nothing further is read from the input. */
    public void stop()
    {
        _root._stopped = true;
    }

    /**
     * The rest of the payload as an array. It fails, and the box is reported, when more bytes
     * remain than the walk's limit on what a reader may read into memory
     * ({@link IsoBoxReaders#DEFAULT_MAX_BUFFERED_PAYLOAD} unless the walk was built with another),
     * so a box that claims a giant size costs no memory; {@link #payload()} streams without limit.
     */
    @NotNull
    public byte[] payloadBytes() throws IOException
    {
        IsoBoxReaders readers = _root._readers;
        long payloadSize = box().payloadSize;
        if (readers != null && payloadSize >= 0) {
            long remaining = payloadSize - (_payload == null ? 0 : _payload.getPosition());
            if (remaining > readers.maxBufferedPayload())
                throw new IOException("Payload (" + remaining + " bytes) is larger than the " + readers.maxBufferedPayload()
                    + " bytes a reader may read into memory");
        }
        if (_iterator != null)
            return _iterator.payloadBytes();
        SequentialReader payload = payload();
        return payload.getBytes((int) (_bytes.length - payload.getPosition()));
    }

    void stream(@NotNull IsoBoxIterator iterator)
    {
        _iterator = iterator;
        _bytes = null;
        _payload = null;
    }

    /** As {@link #stream}, for skipping through the payload: it is never read in one piece. */
    void streamUnbuffered(@NotNull IsoBoxIterator iterator)
    {
        _iterator = iterator;
        _bytes = null;
        SequentialReader payload = iterator.payload();
        if (payload instanceof BoundedSequentialReader)
            ((BoundedSequentialReader) payload).streamed();
        _payload = payload;
    }

    void buffer(@NotNull byte[] bytes)
    {
        _iterator = null;
        _bytes = bytes;
        _payload = new SequentialByteArrayReader(bytes);
    }

    void release()
    {
        _iterator = null;
        _bytes = null;
        _payload = null;
    }

    /** The value stored under {@code key} here or in the nearest enclosing box, or {@code null}. */
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(@NotNull Class<T> key)
    {
        for (IsoBoxContext context = this; context != null; context = context._parent) {
            Object[] values = context._values;
            if (values == null)
                continue;
            for (int i = 0; i < values.length && values[i] != null; i += 2) {
                if (values[i] == key)
                    return (T) values[i + 1];
            }
        }
        return null;
    }

    /** Stores {@code value} on this box, visible to it and everything inside it. */
    public <T> void put(@NotNull Class<T> key, @NotNull T value)
    {
        if (_values == null)
            _values = new Object[4];
        int i = 0;
        while (i < _values.length && _values[i] != null && _values[i] != key)
            i += 2;
        if (i == _values.length)
            _values = Arrays.copyOf(_values, _values.length * 2);
        _values[i] = key;
        _values[i + 1] = value;
    }
}
