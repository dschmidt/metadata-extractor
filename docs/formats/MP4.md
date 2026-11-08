# ISO base media / MP4

MP4 uses a hierarchy of boxes. Boxes share a size/type header; their remaining
layout depends on the box type. A box may contain data or child boxes. Metadata
interpretation can depend on the enclosing handler, so a matching four-character
code alone is not enough to identify a payload's meaning.

## Implementation mapping

- Java: `Source/com/drew/imaging/mp4/Mp4Readers.java` registers readers by box
  path on the shared ISO base media walk (`com.drew.imaging.isobmff`, layout
  `QuickTimeLayout`, which is the standard layout with QuickTime's deviations:
  `moov/meta` and `trak/meta` without a FullBox header, the `keys` preamble, and
  the sound sample description versions). The readers fill the directories of
  `Source/com/drew/metadata/mp4/` for MP4 and QuickTime files alike.
  `Mp4MetadataReader.readMetadata(IsoInput)` takes a stream, a file, or a
  `RandomAccessReader`; `ImageMetadataReader.readMetadata(File)` reads MP4 as a
  file. Sample data that `IsoSamples` asks for from an `mdat` before `moov` is
  read by position from a file, and from a stream only while `mdat` fits
  `IsoBoxReaders.DEFAULT_MAX_BUFFERED_PAYLOAD` (64 MiB). Each sample carries its
  decoding time and its presentation time from the track's edit list (`elst`)
  and composition offsets (`ctts`).
- .NET: MP4 metadata is read by
  `MetadataExtractor/Formats/QuickTime/QuickTimeMetadataReader.cs`.
  `MetadataExtractor/Formats/Iso14496/` contains box infrastructure used by the
  HEIF reader. It is not a separate equivalent of Java's MP4 metadata package.

This is an intentional implementation-layout difference, not evidence that a
particular metadata feature is missing. Supported tags, media properties, box
boundary handling, and resource limits must be compared individually.
No exhaustive MP4 feature-parity check is implied by this documentation migration.

## Java-only behavior

- A track's `mdia/hdlr` handler type chooses its directory: `vide`
  (`Mp4VideoDirectory`), `soun` (`Mp4SoundDirectory`), `hint`, `text`, `meta`,
  and QuickTime's `tmcd` (`Mp4TimecodeDirectory`), `sbtl`
  (`Mp4SubtitleDirectory`) and `musi` (`Mp4MusicDirectory`). The track's own
  `mdhd` dates the directory and gives the time scale of `stts`, whichever of the
  two comes first; a language of all zero bits is left out. The video frame rate
  is the sample count over the media's duration, as ExifTool and ffprobe report
  it; the most common sample duration would misstate tracks timed in
  milliseconds, where 30 frames a second alternate between 33 and 34.
- `mvhd` fills `Mp4Directory` with QuickTime's preview, poster, selection and
  current times as well, which ISO/IEC 14496-12 leaves pre-defined; the video
  sample description gives `Mp4VideoDirectory` the vendor, temporal and spatial
  quality and color table ID, pre-defined in the ISO form too.
- A `meta` whose `hdlr` is `mdta` (keys and values) or `mdir` (an iTunes-style
  list), at the top level, under `moov`, `udta` or a track, fills a
  `QuickTimeMetadataDirectory` of `Source/com/drew/metadata/mov/metadata/`, the
  one class of that package that is not deprecated. Value types read: UTF-8
  strings, JPEG, PNG and BMP data, signed and unsigned integers of 1, 2, 4 or 8
  bytes (types 21, 22 and 65 to 78), 32-bit and 64-bit floats, and arrays of
  32-bit integers. `udta` is also read for the ISO 6709 location (`©xyz`), title,
  comment and Windows Media `Xtra` tags.
- `XMP_` anywhere a file-level atom can be adds an XMP directory; Canon's `CNTH`
  gives `Mp4Directory` the date and time of its Exif thumbnail.
- An Apple Live Photo's movie gets a `LivePhotoDirectory` with the still image
  time: the `mebx` track keyed `com.apple.quicktime.still-image-time` holds one
  sample, and the empty edit before it in `elst` is the moment the photo was
  taken, in the movie's time scale; without an empty edit the photo is the first
  frame, and a track with more than one sample gives no time. This reads `moov`
  alone. `Mp4MetadataReader.LIVE_PHOTO_TRANSFORM_READER`, not among the defaults,
  adds the transform from the video frame to the photo and its reference
  dimensions from that sample. Apple writes `mdat` before `moov`, so give it a
  file, or expect the walk to keep `mdat` in memory up to the buffer limit when
  reading a stream. The photo's side of the pair is matched by the content
  identifier, `com.apple.quicktime.content.identifier` in the movie's `moov/meta`
  and the Apple makernote's tag 0x0011 in the HEIC or JPEG.

## References

- ISO/IEC 14496-12:2015, as cited by the original Java package documentation:
  [historical download](http://standards.iso.org/ittf/PubliclyAvailableStandards/c068960_ISO_IEC_14496-12_2015.zip)
  and [publicly available standards index](https://standards.iso.org/ittf/PubliclyAvailableStandards/index.html).
  Check which edition applies to the feature under investigation.
- [QuickTime media data structures](https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/QTFFChap3/qtff3.html)
  provide related background, not a replacement for MP4's specification.
