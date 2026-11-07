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
import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;

import java.io.EOFException;
import java.io.IOException;

/**
 * A sequential view of a {@link RandomAccessReader}, for walking the boxes of an input that can
 * also be read by position. Small reads come from a read-ahead buffer, so box headers do not
 * cost a seek each; large reads go to the reader directly.
 */
final class SeekingSequentialReader extends SequentialReader
{
    private static final int BUFFER_SIZE = 8 * 1024;

    @NotNull
    private final RandomAccessReader _reader;
    private final long _length;
    private long _position;
    private final byte[] _buffer = new byte[BUFFER_SIZE];
    private long _bufferStart;
    private int _bufferLength;

    SeekingSequentialReader(@NotNull RandomAccessReader reader) throws IOException
    {
        _reader = reader;
        _length = reader.getLength();
    }

    /** The reader's index for input position {@code position}; readers address up to 2 GiB. */
    static int index(long position) throws IOException
    {
        if (position > Integer.MAX_VALUE)
            throw new IOException("Input position " + position + " is beyond the 2 GiB a random access reader addresses");
        return (int) position;
    }

    @Override
    public long getPosition()
    {
        return _position;
    }

    @Override
    public byte getByte() throws IOException
    {
        if (_position >= _length)
            throw new EOFException("End of data reached.");
        if (_position < _bufferStart || _position >= _bufferStart + _bufferLength)
            fill();
        return _buffer[(int) (_position++ - _bufferStart)];
    }

    private void fill() throws IOException
    {
        _bufferLength = (int) Math.min(BUFFER_SIZE, _length - _position);
        byte[] bytes = _reader.getBytes(index(_position), _bufferLength);
        System.arraycopy(bytes, 0, _buffer, 0, _bufferLength);
        _bufferStart = _position;
    }

    @NotNull
    @Override
    public byte[] getBytes(int count) throws IOException
    {
        byte[] bytes = new byte[count];
        getBytes(bytes, 0, count);
        return bytes;
    }

    @Override
    public void getBytes(@NotNull byte[] buffer, int offset, int count) throws IOException
    {
        if (count < 0 || _position + count > _length)
            throw new EOFException("End of data reached.");
        if (count > BUFFER_SIZE) {
            System.arraycopy(_reader.getBytes(index(_position), count), 0, buffer, offset, count);
            _position += count;
            return;
        }
        if (_position < _bufferStart || _position + count > _bufferStart + _bufferLength)
            fill();
        System.arraycopy(_buffer, (int) (_position - _bufferStart), buffer, offset, count);
        _position += count;
    }

    @Override
    public void skip(long n) throws IOException
    {
        if (n < 0)
            throw new IllegalArgumentException("n must be zero or greater.");
        if (!trySkip(n))
            throw new EOFException("Unable to skip. Requested " + n + " bytes but fewer remained.");
    }

    @Override
    public boolean trySkip(long n) throws IOException
    {
        if (n < 0)
            throw new IllegalArgumentException("n must be zero or greater.");
        if (n > _length - _position) {
            _position = _length;
            return false;
        }
        _position += n;
        return true;
    }

    @Override
    public int available()
    {
        return (int) Math.min(Integer.MAX_VALUE, _length - _position);
    }
}
