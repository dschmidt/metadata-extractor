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

import com.drew.lang.RandomAccessReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * What {@link IsoBoxReaders#read(IsoInput, IsoBoxContext)} reads: a stream, a file, or a
 * {@link RandomAccessReader}. A format's reader takes one of these and need not care which,
 * and the walk reads a stream once from the start, while it reads a file or a random access
 * reader by position as well, for data that lies before the boxes that locate it (see
 * {@link IsoBoxContext#requestRange}).
 */
public final class IsoInput
{
    @Nullable
    final InputStream stream;
    @Nullable
    final File file;
    @Nullable
    final RandomAccessReader reader;

    private IsoInput(@Nullable InputStream stream, @Nullable File file, @Nullable RandomAccessReader reader)
    {
        this.stream = stream;
        this.file = file;
        this.reader = reader;
    }

    /** A stream, positioned at the start of the boxes; it is read once and not closed. */
    @NotNull
    public static IsoInput of(@NotNull InputStream stream)
    {
        return new IsoInput(stream, null, null);
    }

    /** A file; it is opened for the walk and closed after it. */
    @NotNull
    public static IsoInput of(@NotNull File file)
    {
        return new IsoInput(null, file, null);
    }

    /** A random access reader over the whole input; it addresses up to 2 GiB. */
    @NotNull
    public static IsoInput of(@NotNull RandomAccessReader reader)
    {
        return new IsoInput(null, null, reader);
    }

    /** An input the walk can read by position. */
    interface Seekable
    {
        long length() throws IOException;

        /** Reads {@code count} bytes at input position {@code position} into {@code buffer}. */
        void read(long position, @NotNull byte[] buffer, int offset, int count) throws IOException;
    }

    static final class SeekableFile implements Seekable
    {
        @NotNull
        private final RandomAccessFile _file;

        SeekableFile(@NotNull RandomAccessFile file)
        {
            _file = file;
        }

        @Override
        public long length() throws IOException
        {
            return _file.length();
        }

        @Override
        public void read(long position, @NotNull byte[] buffer, int offset, int count) throws IOException
        {
            _file.seek(position);
            _file.readFully(buffer, offset, count);
        }
    }

    static final class SeekableReader implements Seekable
    {
        @NotNull
        private final RandomAccessReader _reader;

        SeekableReader(@NotNull RandomAccessReader reader)
        {
            _reader = reader;
        }

        @Override
        public long length() throws IOException
        {
            return _reader.getLength();
        }

        @Override
        public void read(long position, @NotNull byte[] buffer, int offset, int count) throws IOException
        {
            if (position > Integer.MAX_VALUE)
                throw new IOException("Input position " + position + " is beyond the 2 GiB a random access reader addresses");
            System.arraycopy(_reader.getBytes((int) position, count), 0, buffer, offset, count);
        }
    }
}
