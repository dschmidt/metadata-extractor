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

import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.Metadata;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class IsoBoxReadersTest
{
    private static final IsoBoxLayout PLAIN = new IsoBoxLayout()
    {
        @Override
        public boolean enterContainer(@NotNull IsoBoxContext box)
        {
            return true;
        }
    };

    private static final String USERTYPE = "00112233-4455-6677-8899-aabbccddeeff";
    private static final byte[] USERTYPE_BYTES = {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff};

    @Test
    public void testOnlyRegisteredPathsAreRead() throws Exception
    {
        Run run = run(PLAIN, join(
            box("moov", join(box("trak", box("tkhd", new byte[] {1})), box("mvhd", new byte[] {2}))),
            box("mdat", new byte[1000]),
            box("free", new byte[] {3})),
            "moov/trak/tkhd", "free");
        assertEquals(Arrays.asList("moov/trak/tkhd=1", "free=3"), run.reads);
        assertTrue(run.errors.isEmpty());
    }

    @Test
    public void testWildcardMatchesAnyBox() throws Exception
    {
        Run run = run(PLAIN, box("ilst", join(box("Anam", box("data", new byte[] {1})), box("Aart", box("data", new byte[] {2})))),
            "ilst/*/data");
        assertEquals(Arrays.asList("ilst/Anam/data=1", "ilst/Aart/data=2"), run.reads);
    }

    @Test
    public void testUuidPathsMatchTheirUsertype() throws Exception
    {
        byte[] other = new byte[16];
        Run run = run(PLAIN, join(box("uuid", join(USERTYPE_BYTES, new byte[] {1})), box("uuid", join(other, new byte[] {2}))),
            "uuid:" + USERTYPE);
        assertEquals(Collections.singletonList("uuid=1"), run.reads);
        run = run(PLAIN, join(box("uuid", join(USERTYPE_BYTES, new byte[] {1})), box("uuid", join(other, new byte[] {2}))),
            "uuid");
        assertEquals(Arrays.asList("uuid=1", "uuid=2"), run.reads);
    }

    @Test
    public void testSeveralReadersEachReadTheWholePayloadInOrder() throws Exception
    {
        final List<String> reads = new ArrayList<String>();
        IsoBoxReader first = recorder("first", reads, "hdlr");
        IsoBoxReader second = recorder("second", reads, "hdlr");
        read(new IsoBoxReaders(PLAIN, Arrays.asList(first, second)), box("hdlr", new byte[] {7, 8}), new ArrayList<String>());
        assertEquals(Arrays.asList("first:hdlr=7", "second:hdlr=7"), reads);
    }

    @Test
    public void testPositionsAreTheSameWithOneReaderOrSeveral() throws Exception
    {
        for (int size : new int[] {100, 40 * 1024}) {
            final List<String> positions = new ArrayList<String>();
            IsoBoxReaders.Callback callback = new IsoBoxReaders.Callback()
            {
                @Override
                public void read(@NotNull IsoBoxContext box) throws IOException
                {
                    long before = box.payload().getPosition();
                    box.payload().getUInt32();
                    positions.add(before + "-" + box.payload().getPosition());
                }
            };
            byte[] file = join(box("free", new byte[12]), box("data", new byte[size]));
            IsoBoxReaders one = new IsoBoxReaders(PLAIN, Collections.singletonList(IsoBoxReaders.on(callback, "data")));
            IsoBoxReaders two = new IsoBoxReaders(PLAIN, Arrays.asList(IsoBoxReaders.on(callback, "data"), IsoBoxReaders.on(callback, "data")));
            for (IsoBoxReaders readers : Arrays.asList(one, two))
                readers.read(new IsoBoxIterator(new ByteArrayInputStream(file)), IsoBoxContext.root(new Metadata(), collect(new ArrayList<String>())));
            assertEquals(Arrays.asList("0-4", "0-4", "0-4"), positions);
        }
    }

    @Test
    public void testABoxCanBeReadAndDescendedInto() throws Exception
    {
        IsoBoxLayout header = new IsoBoxLayout()
        {
            @Override
            public boolean enterContainer(@NotNull IsoBoxContext box) throws IOException
            {
                if (box.box().type.equals("stsd"))
                    box.payload().skip(8);
                return true;
            }
        };
        Run run = run(header, box("stsd", join(new byte[] {0, 0, 0, 0, 0, 0, 0, 1}, box("mp4a", new byte[] {5}))),
            "stsd", "stsd/mp4a");
        assertEquals(Arrays.asList("stsd=0", "stsd/mp4a=5"), run.reads);
    }

    @Test
    public void testLayoutCanRefuseToDescend() throws Exception
    {
        IsoBoxLayout none = new IsoBoxLayout()
        {
            @Override
            public boolean enterContainer(@NotNull IsoBoxContext box)
            {
                return false;
            }
        };
        assertTrue(run(none, box("moov", box("mvhd", new byte[] {1})), "moov/mvhd").reads.isEmpty());
    }

    @Test
    public void testStateIsScopedToItsContainer() throws Exception
    {
        final List<String> seen = new ArrayList<String>();
        IsoBoxReader handler = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                box.ancestor("trak").put(String.class, "track" + box.payload().getByte());
            }
        }, "moov/trak/mdia/hdlr");
        IsoBoxReader sample = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box)
            {
                seen.add(box.get(String.class));
            }
        }, "moov/trak/stbl/stsd", "moov/udta");
        byte[] file = box("moov", join(
            box("trak", join(box("mdia", box("hdlr", new byte[] {1})), box("stbl", box("stsd", new byte[0])))),
            box("trak", join(box("mdia", box("hdlr", new byte[] {2})), box("stbl", box("stsd", new byte[0])))),
            box("udta", new byte[0])));
        read(new IsoBoxReaders(PLAIN, Arrays.asList(handler, sample)), file, new ArrayList<String>());
        assertEquals(Arrays.asList("track1", "track2", null), seen);
    }

    @Test
    public void testAFailingReaderDoesNotStopTheWalk() throws Exception
    {
        IsoBoxReader failing = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                throw new IOException("bad");
            }
        }, "fail");
        final List<String> reads = new ArrayList<String>();
        List<String> errors = new ArrayList<String>();
        read(new IsoBoxReaders(PLAIN, Arrays.asList(failing, recorder("ok", reads, "next"))),
            join(box("fail", new byte[1]), box("next", new byte[] {4})), errors);
        assertEquals(Collections.singletonList("Error reading box 'fail': bad (IOException)"), errors);
        assertEquals(Collections.singletonList("ok:next=4"), reads);
    }

    @Test
    public void testTruncatedInputIsReportedOnceAndEndsTheWalk() throws Exception
    {
        List<String> errors = new ArrayList<String>();
        IsoBoxReader reader = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                box.payloadBytes();
            }
        }, "moov/trak/mdia/mdhd");
        byte[] file = join(header(8 + 8 + 8 + 8 + 100, "moov"), header(8 + 8 + 8 + 100, "trak"), header(8 + 8 + 100, "mdia"), header(8 + 100, "mdhd"), new byte[10]);
        read(new IsoBoxReaders(PLAIN, Collections.singletonList(reader)), file, errors);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).endsWith("(TruncatedInputException)"));
    }

    @Test
    public void testTruncatedSmallPayloadIsReportedOnce() throws Exception
    {
        List<String> errors = new ArrayList<String>();
        IsoBoxReader reader = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                box.payload().getUInt32();
                box.payload().getUInt32();
            }
        }, "moov/mvhd");
        // moov claims more than is read in one piece, so it is streamed and mvhd alone is read into memory
        read(new IsoBoxReaders(PLAIN, Collections.singletonList(reader)), join(header(8 + 8 + 100 + 20000, "moov"), header(8 + 100, "mvhd"), new byte[6]), errors);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).endsWith("(TruncatedInputException)"));
    }

    @Test
    public void testSharedPayloadsAboveTheLimitAreSkipped() throws Exception
    {
        final List<String> reads = new ArrayList<String>();
        List<String> errors = new ArrayList<String>();
        IsoBoxReaders readers = new IsoBoxReaders(PLAIN, Arrays.asList(recorder("a", reads, "data"), recorder("b", reads, "data")), 10);
        IsoBoxContext root = IsoBoxContext.root(new Metadata(), collect(errors));
        readers.read(new IsoBoxIterator(new ByteArrayInputStream(join(box("data", new byte[100]), box("data", new byte[] {1})))), root);
        assertEquals(Arrays.asList("a:data=1", "b:data=1"), reads);
        assertEquals(1, errors.size());
    }

    @Test
    public void testBoxesInsideASharedPayloadKeepInputOffsets() throws Exception
    {
        final List<Long> offsets = new ArrayList<Long>();
        IsoBoxReader offset = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box)
            {
                offsets.add(box.box().offset);
            }
        }, "udta/meta");
        List<String> reads = new ArrayList<String>();
        read(new IsoBoxReaders(PLAIN, Arrays.asList(recorder("udta", reads, "udta"), offset)),
            join(box("free", new byte[4]), box("udta", join(box("Axyz", new byte[2]), box("meta", new byte[0])))), new ArrayList<String>());
        assertEquals(Collections.singletonList(12L + 8 + 10), offsets);
    }

    @Test
    public void testAWalkCanBeReusedForSeveralFiles() throws Exception
    {
        final List<String> reads = new ArrayList<String>();
        IsoBoxReaders readers = new IsoBoxReaders(PLAIN, Collections.singletonList(recorder("r", reads, "moov/mvhd")));
        read(readers, box("moov", box("mvhd", new byte[] {1})), new ArrayList<String>());
        read(readers, box("moov", box("mvhd", new byte[] {2})), new ArrayList<String>());
        assertEquals(Arrays.asList("r:moov/mvhd=1", "r:moov/mvhd=2"), reads);
    }

    @Test
    public void testReadersRunInTheOrderPassedWhateverTheirPattern() throws Exception
    {
        List<String> reads = new ArrayList<String>();
        String usertype = "uuid:00112233-4455-6677-8899-aabbccddeeff";
        byte[] uuidBox = join(header(8 + 16 + 1, "uuid"),
            new byte[] {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0xff},
            new byte[] {1});
        byte[] file = join(uuidBox, box("stsd", box("mebx", new byte[] {2})));
        read(new IsoBoxReaders(PLAIN, Arrays.asList(recorder("specific", reads, usertype), recorder("any", reads, "uuid"),
            recorder("wildcard", reads, "stsd/*"), recorder("exact", reads, "stsd/mebx"))), file, new ArrayList<String>());
        assertEquals(Arrays.asList("specific:uuid=1", "any:uuid=1", "wildcard:stsd/mebx=2", "exact:stsd/mebx=2"), reads);
    }

    @Test
    public void testChildrenKeepTheNestingDepth() throws Exception
    {
        byte[] nested = box("leaf", new byte[0]);
        for (int i = 0; i < IsoBoxIterator.MAX_DEPTH + 2; i++)
            nested = box("nest", nested);
        byte[] file = box("outr", box("innr", nested));
        final List<Integer> levels = new ArrayList<Integer>();
        IsoBoxReaders.Callback descend = new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                int level = 0;
                try {
                    IsoBoxIterator boxes = box.children();
                    while (boxes.next() != null) {
                        boxes = boxes.children();
                        level++;
                    }
                } catch (IOException e) {
                    levels.add(level);
                }
            }
        };
        // innr is a level below the top; streamed, or in memory for two readers
        read(new IsoBoxReaders(PLAIN, Collections.singletonList(IsoBoxReaders.on(descend, "outr/innr"))), file, new ArrayList<String>());
        read(new IsoBoxReaders(PLAIN, Arrays.asList(IsoBoxReaders.on(descend, "outr/innr"), IsoBoxReaders.on(descend, "outr/innr"))), file, new ArrayList<String>());
        assertEquals(Arrays.asList(IsoBoxIterator.MAX_DEPTH - 2, IsoBoxIterator.MAX_DEPTH - 2, IsoBoxIterator.MAX_DEPTH - 2), levels);
    }

    @Test
    public void testAReaderThatLeavesThePayloadAloneReadsNothingOfIt() throws Exception
    {
        IsoBoxReader header = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box)
            {
            }
        }, "skip");
        byte[] file = join(box("skip", new byte[1000]), box("next", new byte[0]));
        final int[] read = new int[1];
        InputStream in = new FilterInputStream(new ByteArrayInputStream(file))
        {
            @Override
            public int read() throws IOException
            {
                int b = super.read();
                if (b >= 0)
                    read[0]++;
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException
            {
                int n = super.read(buffer, offset, length);
                read[0] += Math.max(n, 0);
                return n;
            }
        };
        new IsoBoxReaders(PLAIN, Collections.singletonList(header)).read(new IsoBoxIterator(in), IsoBoxContext.root(new Metadata(), collect(new ArrayList<String>())));
        // two box headers, and the last skipped byte, which the iterator reads to check that it is there
        assertEquals(8 + 1 + 8, read[0]);
    }

    @Test
    public void testProblemsComeWithTheBoxTheyAreIn() throws Exception
    {
        IsoBoxReader boom = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                throw new IOException("bad");
            }
        }, "boom", "cont/kidd");
        // a reader that throws; a container whose child claims more than it holds; a box cut off by the end of input
        byte[] file = join(box("boom", new byte[1]), box("cont", join(header(100, "kidd"), new byte[4])), header(100, "tail"));
        final List<String> boxes = new ArrayList<String>();
        IsoBoxContext root = IsoBoxContext.root(new Metadata(), new IsoBoxContext.ErrorHandler()
        {
            @Override
            public void error(@Nullable IsoBox box, @NotNull String message, @Nullable IOException cause)
            {
                boxes.add(box == null ? "file" : box.type);
            }
        });
        new IsoBoxReaders(PLAIN, Collections.singletonList(boom)).read(new IsoBoxIterator(new ByteArrayInputStream(file)), root);
        assertEquals(Arrays.asList("boom", "cont", "file"), boxes);
    }

    @Test
    public void testSizeZeroInsideAContainerIsReadToItsEndAndReported() throws Exception
    {
        byte[] file = join(box("moov", join(header(0, "trak"), box("tkhd", new byte[] {1}))), header(0, "mdat"), new byte[] {2});
        Run run = run(PLAIN, file, "moov/trak", "moov/trak/tkhd", "mdat");
        assertEquals(Arrays.asList("moov/trak=0", "moov/trak/tkhd=1", "mdat=2"), run.reads);
        assertEquals(Collections.singletonList("Box 'trak' declares size 0 inside 'moov'; read to the end of 'moov'"), run.errors);
    }

    @Test
    public void testQuickTimeZeroTerminatorIsNotReported() throws Exception
    {
        byte[] file = join(box("moov", box("udta", join(box("name", new byte[] {1}), new byte[4]))), box("mdat", new byte[] {2}));
        Run run = run(PLAIN, file, "moov/udta/name", "mdat");
        assertEquals(Arrays.asList("moov/udta/name=1", "mdat=2"), run.reads);
        assertEquals(Collections.emptyList(), run.errors);
    }

    @Test
    public void testAReaderCanTakeOnlyTheFirstChild() throws Exception
    {
        final List<String> taken = new ArrayList<String>();
        IsoBoxReaders.Callback first = new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                IsoBox child = box.children().next();
                taken.add(child.type + "@" + child.offset);
            }
        };
        byte[] file = join(box("free", new byte[4]), box("ilst", box("Anam", join(box("data", new byte[] {1}), box("data", new byte[] {2})))));
        read(new IsoBoxReaders(PLAIN, Collections.singletonList(IsoBoxReaders.on(first, "ilst/*"))), file, new ArrayList<String>());
        read(new IsoBoxReaders(PLAIN, Arrays.asList(IsoBoxReaders.on(first, "ilst/*"), IsoBoxReaders.on(first, "ilst/*"))), file, new ArrayList<String>());
        // free (12) + ilst header (8) + Anam header (8)
        assertEquals(Arrays.asList("data@28", "data@28", "data@28"), taken);
    }

    @Test
    public void testWhenDoneRunsAfterTheChildren() throws Exception
    {
        final List<String> reads = new ArrayList<String>();
        IsoBoxReader child = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box)
            {
                reads.add(box.box().type);
                if (box.parent().get(String.class) == null) {
                    box.parent().put(String.class, "registered");
                    box.parent().whenDone(new IsoBoxReaders.Callback()
                    {
                        @Override
                        public void read(@NotNull IsoBoxContext done)
                        {
                            reads.add("done:" + done.box().type);
                        }
                    });
                    box.root().whenDone(new IsoBoxReaders.Callback()
                    {
                        @Override
                        public void read(@NotNull IsoBoxContext done)
                        {
                            reads.add("done:file");
                        }
                    });
                }
            }
        }, "trak/*");
        byte[] file = join(box("trak", join(box("tkhd", new byte[0]), box("mdia", new byte[0]))), box("free", new byte[0]));
        read(new IsoBoxReaders(PLAIN, Collections.singletonList(child)), file, new ArrayList<String>());
        assertEquals(Arrays.asList("tkhd", "mdia", "done:trak", "done:file"), reads);
    }

    @Test
    public void testStopEndsTheWalk() throws Exception
    {
        final List<String> reads = new ArrayList<String>();
        IsoBoxReader stopper = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box)
            {
                reads.add(box.path());
                box.stop();
            }
        }, "moov/mvhd");
        IsoBoxReader later = recorder("later", reads, "free");
        byte[] head = join(box("moov", box("mvhd", new byte[4])), header(8 + 10000000, "mdat"));
        final int[] read = new int[1];
        InputStream in = new SequenceInputStream(new ByteArrayInputStream(head), new InputStream()
        {
            @Override
            public int read()
            {
                read[0]++;
                return 0;
            }
        });
        IsoBoxContext root = IsoBoxContext.root(new Metadata(), collect(new ArrayList<String>()));
        new IsoBoxReaders(PLAIN, Arrays.asList(stopper, later)).read(new IsoBoxIterator(in), root);
        assertEquals(Collections.singletonList("moov/mvhd"), reads);
        assertEquals(0, read[0]);
    }

    @Test
    public void testDecompressedBoxesAreWalkedWithTheSameReaders() throws Exception
    {
        final List<String> reads = new ArrayList<String>();
        IsoBoxReader cmov = IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                // stands in for inflating cmvd: the payload already is the moov box
                box.root().walkBoxes(box.payloadBytes());
            }
        }, "moov/cmov");
        byte[] inner = box("moov", box("mvhd", new byte[] {7}));
        read(new IsoBoxReaders(PLAIN, Arrays.asList(cmov, recorder("r", reads, "moov/mvhd"))), box("moov", box("cmov", inner)), new ArrayList<String>());
        assertEquals(Collections.singletonList("r:moov/mvhd=7"), reads);
    }

    private static IsoBoxReaders rangeRequester(final List<String> got, final long... offsets)
    {
        return rangeRequester(got, false, IsoBoxReaders.DEFAULT_MAX_BUFFERED_PAYLOAD, offsets);
    }

    /**
     * A walk whose reader of {@code moov/stco} asks for two bytes at each of {@code offsets};
     * if {@code locating}, it names {@code moov} as the box that locates them.
     */
    private static IsoBoxReaders rangeRequester(final List<String> got, final boolean locating, long maxBuffered, final long... offsets)
    {
        return new IsoBoxReaders(PLAIN, Collections.singletonList(new IsoBoxReader()
        {
            @NotNull
            @Override
            public Iterable<String> getBoxPaths()
            {
                return Collections.singletonList("moov/stco");
            }

            @NotNull
            @Override
            public Iterable<String> getLocatingPaths()
            {
                return locating ? Collections.singletonList("moov") : Collections.<String>emptyList();
            }

            @Override
            public void readBox(@NotNull IsoBoxContext box)
            {
                for (final long offset : offsets) {
                    box.requestRange(offset, 2, new IsoBoxContext.RangeReader()
                    {
                        @Override
                        public void read(@NotNull IsoBoxContext data, @NotNull SequentialReader reader) throws IOException
                        {
                            String in = data.parent() == null ? "file" : data.box().type;
                            got.add(in + "@" + offset + "=" + reader.getUInt8() + "," + reader.getUInt8());
                        }
                    });
                }
            }
        }), maxBuffered);
    }

    @Test
    public void testBoxesBeforeALocatingBoxAreKeptForItsRanges() throws Exception
    {
        List<String> got = new ArrayList<String>();
        byte[] file = join(box("mdat", new byte[] {1, 2, 3}), box("moov", box("stco", new byte[0])));
        List<String> errors = new ArrayList<String>();
        IsoBoxContext root = IsoBoxContext.root(new Metadata(), collect(errors));
        rangeRequester(got, true, 64, 9).read(new ByteArrayInputStream(file), root);
        assertEquals(Collections.singletonList("mdat@9=2,3"), got);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void testNothingIsKeptOnceTheLocatingBoxesAreRead() throws Exception
    {
        byte[] file = join(box("moov", box("stco", new byte[0])), box("mdat", new byte[1000]));
        final int[] read = new int[1];
        InputStream in = new FilterInputStream(new ByteArrayInputStream(file))
        {
            @Override
            public int read() throws IOException
            {
                int b = super.read();
                if (b >= 0)
                    read[0]++;
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException
            {
                int n = super.read(buffer, offset, length);
                read[0] += Math.max(n, 0);
                return n;
            }
        };
        rangeRequester(new ArrayList<String>(), true, 64 * 1024).read(in, IsoBoxContext.root(new Metadata(), collect(new ArrayList<String>())));
        // three box headers, and the last skipped byte of 'mdat', which the iterator reads to check that it is there
        assertEquals(8 + 8 + 8 + 1, read[0]);
    }

    @Test
    public void testBoxesAboveTheBufferLimitAreNotKept() throws Exception
    {
        List<String> got = new ArrayList<String>();
        byte[] file = join(box("mdat", new byte[] {1, 2, 3}), box("moov", box("stco", new byte[0])));
        List<String> errors = new ArrayList<String>();
        IsoBoxContext root = IsoBoxContext.root(new Metadata(), collect(errors));
        rangeRequester(got, true, 2, 9).read(new ByteArrayInputStream(file), root);
        assertTrue(got.isEmpty());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("not read"));
    }

    @Test
    public void testFreeBoxesAreNotKept() throws Exception
    {
        List<String> got = new ArrayList<String>();
        byte[] file = join(box("free", new byte[] {1, 2, 3}), box("moov", box("stco", new byte[0])));
        List<String> errors = new ArrayList<String>();
        rangeRequester(got, true, 64, 9).read(new ByteArrayInputStream(file), IsoBoxContext.root(new Metadata(), collect(errors)));
        assertTrue(got.isEmpty());
        assertEquals(1, errors.size());
    }

    @Test
    public void testRangesBeforeTheirLocationAreReadByPositionFromAFile() throws Exception
    {
        List<String> got = new ArrayList<String>();
        byte[] bytes = join(box("mdat", new byte[] {1, 2, 3, 4, 5, 6}), box("moov", box("stco", new byte[0])));
        File file = File.createTempFile("boxes", ".mp4");
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(bytes);
            out.close();
            List<String> errors = new ArrayList<String>();
            IsoBoxContext root = IsoBoxContext.root(new Metadata(), collect(errors));
            // 'moov' is not named as locating, so nothing is kept; the file is read by position instead
            rangeRequester(got, false, 64, 12, 9, 100).read(IsoInput.of(file), root);
            assertEquals(Arrays.asList("file@9=2,3", "file@12=5,6"), got);
            assertEquals(1, errors.size());
            assertTrue(errors.get(0), errors.get(0).contains("outside the input"));
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void testAnInputOfAStreamReadsLikeTheStream() throws Exception
    {
        List<String> got = new ArrayList<String>();
        byte[] file = join(box("mdat", new byte[] {1, 2, 3}), box("moov", box("stco", new byte[0])));
        List<String> errors = new ArrayList<String>();
        rangeRequester(got, true, 64, 9).read(IsoInput.of(new ByteArrayInputStream(file)), IsoBoxContext.root(new Metadata(), collect(errors)));
        assertEquals(Collections.singletonList("mdat@9=2,3"), got);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void testRangesBeyondTwoGibAreReadByPositionFromAFile() throws Exception
    {
        // 'mdat' with a 64-bit size of 2.5 GiB, then 'moov'; the walk passes 'mdat' by position arithmetic alone
        final long mdatSize = 2560L * 1024 * 1024;
        final byte[] mdatHeader = ByteBuffer.allocate(16).putInt(1).put("mdat".getBytes("ISO-8859-1")).putLong(mdatSize).array();
        final byte[] moov = box("moov", box("stco", new byte[0]));
        IsoInput.Seekable input = new IsoInput.Seekable()
        {
            @Override
            public long length()
            {
                return mdatSize + moov.length;
            }

            @Override
            public void read(long position, @NotNull byte[] buffer, int offset, int count)
            {
                for (int i = 0; i < count; i++) {
                    long at = position + i;
                    buffer[offset + i] = at < 16 ? mdatHeader[(int) at] : at >= mdatSize ? moov[(int) (at - mdatSize)] : (byte) at;
                }
            }
        };
        List<String> got = new ArrayList<String>();
        List<String> errors = new ArrayList<String>();
        long offset = 2400L * 1024 * 1024 + 1;
        rangeRequester(got, false, 64, offset).read(input, IsoBoxContext.root(new Metadata(), collect(errors)));
        assertEquals(Collections.singletonList("file@" + offset + "=" + (offset & 0xff) + "," + ((offset + 1) & 0xff)), got);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void testNothingIsKeptWhenTheInputIsReadByPosition() throws Exception
    {
        List<String> got = new ArrayList<String>();
        byte[] bytes = join(box("mdat", new byte[] {1, 2, 3}), box("moov", box("stco", new byte[0])));
        File file = File.createTempFile("boxes", ".mp4");
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(bytes);
            out.close();
            List<String> errors = new ArrayList<String>();
            // a limit of 2 would stop 'mdat' from being kept, but by position it is not needed
            rangeRequester(got, true, 2, 9).read(IsoInput.of(file), IsoBoxContext.root(new Metadata(), collect(errors)));
            assertEquals(Collections.singletonList("file@9=2,3"), got);
            assertTrue(errors.toString(), errors.isEmpty());
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void testRangesAfterTheirLocationAreReadInOrder() throws Exception
    {
        List<String> got = new ArrayList<String>();
        // moov (8) + stco (8) = 16; mdat payload at 24
        byte[] file = join(box("moov", box("stco", new byte[0])), box("mdat", new byte[] {1, 2, 3, 4, 5, 6}));
        read(rangeRequester(got, 28, 24), file, new ArrayList<String>());
        assertEquals(Arrays.asList("mdat@24=1,2", "mdat@28=5,6"), got);
    }

    @Test
    public void testOverlappingRangesAreEachReadInFull() throws Exception
    {
        List<String> got = new ArrayList<String>();
        // mdat payload at 24; ranges of 2 bytes at 24, 25 and 24 overlap, 28 stands alone
        byte[] file = join(box("moov", box("stco", new byte[0])), box("mdat", new byte[] {1, 2, 3, 4, 5, 6}));
        List<String> errors = new ArrayList<String>();
        read(rangeRequester(got, 24, 25, 24, 28), file, errors);
        assertEquals(Arrays.asList("mdat@24=1,2", "mdat@24=1,2", "mdat@25=2,3", "mdat@28=5,6"), got);
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void testRangesBeforeTheirLocationAreReportedWhenNothingKeepsThem() throws Exception
    {
        List<String> got = new ArrayList<String>();
        byte[] file = join(box("mdat", new byte[] {1, 2, 3}), box("moov", box("stco", new byte[0])));
        List<String> errors = new ArrayList<String>();
        // 'moov' is not named as locating, so 'mdat' is passed unread and the range cannot be served
        rangeRequester(got, 9).read(new ByteArrayInputStream(file), IsoBoxContext.root(new Metadata(), collect(errors)));
        assertTrue(got.isEmpty());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("1 range of data (2 bytes, from input offset 9) not read"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidPathsAreRejected()
    {
        new IsoBoxReaders(PLAIN, Collections.singletonList(recorder("r", new ArrayList<String>(), "moov/toolong")));
    }

    private static Run run(IsoBoxLayout layout, byte[] file, String... paths) throws IOException
    {
        Run run = new Run();
        IsoBoxReader reader = recorder(null, run.reads, paths);
        read(new IsoBoxReaders(layout, Collections.singletonList(reader)), file, run.errors);
        return run;
    }

    private static void read(IsoBoxReaders readers, byte[] file, List<String> errors)
    {
        IsoBoxContext root = IsoBoxContext.root(new Metadata(), collect(errors));
        readers.read(new IsoBoxIterator(new ByteArrayInputStream(file)), root);
    }

    private static IsoBoxContext.ErrorHandler collect(final List<String> errors)
    {
        return new IsoBoxContext.ErrorHandler()
        {
            @Override
            public void error(@Nullable IsoBox box, @NotNull String message, @Nullable IOException cause)
            {
                errors.add(message + (cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")"));
            }
        };
    }

    /** Records the path and the first payload byte (or 0 for an empty payload) of each box it reads. */
    private static IsoBoxReader recorder(@Nullable final String name, final List<String> reads, String... paths)
    {
        return IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                byte[] payload = box.payloadBytes();
                reads.add((name == null ? "" : name + ":") + box.path() + "=" + (payload.length == 0 ? 0 : payload[0]));
            }
        }, paths);
    }

    private static final class Run
    {
        final List<String> reads = new ArrayList<String>();
        final List<String> errors = new ArrayList<String>();
    }

    private static byte[] box(String type, byte[] payload) throws IOException
    {
        return join(header(payload.length + 8, type), payload);
    }

    private static byte[] header(int size, String type) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(size);
        out.write(type.getBytes("ISO-8859-1"));
        return bytes.toByteArray();
    }

    private static byte[] join(byte[]... parts) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts)
            bytes.write(part);
        return bytes.toByteArray();
    }
}
