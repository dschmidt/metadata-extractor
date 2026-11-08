# QuickTime

QuickTime files contain a hierarchy of atoms. Atoms share a size/type header,
but their payloads depend on the atom type. An atom may be a leaf or a container.
Handler context matters: atoms with the same name may need different decoding
depending on their enclosing media or metadata handler.

## Implementation mapping

- Java: `Source/com/drew/imaging/quicktime/QuickTimeReaders.java` registers
  readers by atom path on the shared ISO base media walk
  (`com.drew.imaging.isobmff`, layout `QuickTimeLayout`), and the atom classes in
  `Source/com/drew/metadata/mov/atoms/` decode the payloads into the directories
  of `Source/com/drew/metadata/mov/`. A track's `mdia/hdlr` component type
  chooses its media directory (`vide`, `soun`, `tmcd`, `text`, `sbtl`, `musi`);
  a `meta` atom's `hdlr` chooses between `mdta` keys and values and an `mdir`
  iTunes-style list. The latest `mdhd` dates the media directories and gives the
  time scale of `stts`, as the earlier handler-based reader did.
  `QuickTimeMetadataReader.readMetadata(IsoInput)` takes a stream, a file, or a
  `RandomAccessReader`; `ImageMetadataReader.readMetadata(File)` reads QuickTime
  as a file. `QuickTimeMetadataReader.LIVE_PHOTO_READER`, not among the default
  readers, reads the still image time of an Apple Live Photo's movie into
  `QuickTimeLivePhotoDirectory`: the one sample of the `mebx` track keyed
  `com.apple.quicktime.still-image-time`, presented where its edit list says,
  with the still image transform and its reference dimensions. Apple writes
  `mdat` before `moov`, so give it a file, or expect the walk to keep `mdat` in
  memory up to the buffer limit when reading a stream.
- .NET: `MetadataExtractor/Formats/QuickTime/` uses callbacks for traversal and
  metadata extraction. This reader also handles MP4 metadata.

The Java reader layout is not a requirement for the .NET implementation.
Shared container terminology does not imply equivalent supported tags or media
properties. Compare those individually against the specification and identical
inputs before claiming a capability gap.

## References

- [QuickTime file format specification](https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/)
- [Media data structures](https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/QTFFChap3/qtff3.html)
- [Metadata atoms](https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/Metadata/Metadata.html)

[ISO base media / MP4](MP4.md) describes the related box format and the different
Java/.NET package layouts.

[Canon CR3](CR3.md) describes the Canon-specific metadata also read by the .NET
QuickTime implementation.
