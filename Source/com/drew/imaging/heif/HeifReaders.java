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
package com.drew.imaging.heif;

import com.drew.imaging.isobmff.IsoBoxContext;
import com.drew.imaging.isobmff.IsoBoxReader;
import com.drew.imaging.isobmff.IsoBoxReaders;
import com.drew.lang.SequentialReader;
import com.drew.lang.annotations.NotNull;
import com.drew.lang.annotations.Nullable;
import com.drew.metadata.heif.HeifDirectory;
import com.drew.metadata.heif.boxes.AuxiliaryTypeProperty;
import com.drew.metadata.heif.boxes.Box;
import com.drew.metadata.heif.boxes.ColourInformationBox;
import com.drew.metadata.heif.boxes.FileTypeBox;
import com.drew.metadata.heif.boxes.HandlerBox;
import com.drew.metadata.heif.boxes.ImageRotationBox;
import com.drew.metadata.heif.boxes.ImageSpatialExtentsProperty;
import com.drew.metadata.heif.boxes.PixelInformationBox;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The readers of HEIF files, on the standard layout. The {@code meta} box describes the items; once its
 * {@code hdlr} is {@code pict}, the readers fill a second {@link HeifDirectory} with the item and
 * property boxes. Items are read by {@link com.drew.imaging.isobmff.IsoItems}.
 */
final class HeifReaders
{
    private HeifReaders() {}

    /** The file's {@link HeifDirectory}, and the second one for the picture once {@code hdlr} says {@code pict}. */
    static final class Directories
    {
        final HeifDirectory file;
        @Nullable
        HeifDirectory picture;

        Directories(@NotNull HeifDirectory file)
        {
            this.file = file;
        }
    }

    static final List<IsoBoxReader> ALL_READERS = Collections.unmodifiableList(Arrays.asList(
        IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                HeifDirectory directory = directories(box).file;
                FileTypeBox fileType = new FileTypeBox(box.payload(), new Box(box.box()));
                fileType.addMetadata(directory);
                if (!fileType.getCompatibleBrands().contains("mif1"))
                    directory.addError("File Type Box does not contain required brand, mif1");
            }
        }, "ftyp"),
        IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                if (!"pict".equals(new HandlerBox(box.payload(), new Box(box.box())).getHandlerType()))
                    return;
                Directories directories = directories(box);
                directories.picture = new HeifDirectory();
                box.metadata().addDirectory(directories.picture);
            }
        }, "meta/hdlr"),
        IsoBoxReaders.on(new IsoBoxReaders.Callback()
        {
            @Override
            public void read(@NotNull IsoBoxContext box) throws IOException
            {
                HeifDirectory picture = directories(box).picture;
                if (picture == null)
                    return;
                SequentialReader payload = box.payload();
                Box header = new Box(box.box());
                String type = box.box().type;
                if (type.equals("ispe")) {
                    new ImageSpatialExtentsProperty(payload, header).addMetadata(picture);
                } else if (type.equals("auxC")) {
                    new AuxiliaryTypeProperty(payload, header);
                } else if (type.equals("irot")) {
                    new ImageRotationBox(payload, header).addMetadata(picture);
                } else if (type.equals("colr")) {
                    new ColourInformationBox(payload, header, box.metadata()).addMetadata(picture);
                } else {
                    new PixelInformationBox(payload, header).addMetadata(picture);
                }
            }
        }, "meta/iprp/ipco/ispe", "meta/iprp/ipco/auxC", "meta/iprp/ipco/irot", "meta/iprp/ipco/colr", "meta/iprp/ipco/pixi")));

    @NotNull
    static Directories directories(@NotNull IsoBoxContext box)
    {
        return box.root().get(Directories.class);
    }
}
