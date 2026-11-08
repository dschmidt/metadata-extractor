# QuickTime

QuickTime files contain a hierarchy of atoms. Atoms share a size/type header,
but their payloads depend on the atom type. An atom may be a leaf or a container.
Handler context matters: atoms with the same name may need different decoding
depending on their enclosing media or metadata handler.

## Implementation mapping

- Java: QuickTime files are read by the MP4 readers; see
  [ISO base media / MP4](MP4.md). `Source/com/drew/imaging/mp4/Mp4Readers.java`
  reads QuickTime's atoms into the directories of `Source/com/drew/metadata/mp4/`
  and the shared `QuickTimeMetadataDirectory`, and `ImageMetadataReader` tells
  QuickTime from MP4 only to choose the file type it reports.
  `Source/com/drew/imaging/quicktime/QuickTimeMetadataReader.java` and the
  directories and atom classes of `Source/com/drew/metadata/mov/` are deprecated,
  all but `QuickTimeMetadataDirectory`, which both readers fill. They keep
  working as they are until the next major version: `QuickTimeReaders` registers
  readers by atom path on the shared ISO base media walk
  (`com.drew.imaging.isobmff`, layout `QuickTimeLayout`), and the atom classes
  decode the payloads. A track's `mdia/hdlr` component type chooses its media
  directory (`vide`, `soun`, `tmcd`, `text`, `sbtl`, `musi`); a `meta` atom's
  `hdlr` chooses between `mdta` keys and values and an `mdir` iTunes-style list.
  The latest `mdhd` dates the media directories and gives the time scale of
  `stts`, as the earlier handler-based reader did. The deprecated directories
  keep the tags they have and gain none; new tags, and the Live Photo directory,
  exist only in the MP4 directories. `ImageMetadataReader` keeps giving QuickTime
  files the deprecated directories until the major version.
- .NET: `MetadataExtractor/Formats/QuickTime/` uses callbacks for traversal and
  metadata extraction. This reader also handles MP4 metadata.

The Java reader layout is not a requirement for the .NET implementation.
Shared container terminology does not imply equivalent supported tags or media
properties. Compare those individually against the specification and identical
inputs before claiming a capability gap.

## Java-only behavior

- The deprecated `QuickTimeVideoDirectory` takes its frame rate from the first
  entry of `stts`, so a track that starts with an odd frame duration reports an
  odd rate; `Mp4VideoDirectory` averages over the media's duration.
- The deprecated directories format dates with a numeric offset
  (`+00:00`), the MP4 directories with `UTC`.

## References

- [QuickTime file format specification](https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/)
- [Media data structures](https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/QTFFChap3/qtff3.html)
- [Metadata atoms](https://developer.apple.com/library/archive/documentation/QuickTime/QTFF/Metadata/Metadata.html)

[ISO base media / MP4](MP4.md) describes the related box format, what is read from
QuickTime files, and the different Java/.NET package layouts.

[Canon CR3](CR3.md) describes the Canon-specific metadata also read by the .NET
QuickTime implementation.
