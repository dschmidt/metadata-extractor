# HEIF / HEIC / AVIF

HEIF uses the ISO base media file format. Read [ISO base media / MP4](MP4.md)
as well when changing shared box parsing.

## References

The Java package documentation previously linked this background resource:

- [HEIF thesis (historical repository URL)](https://dspace.cc.tut.fi/dpub/bitstream/handle/123456789/24147/heikkila.pdf?sequence=1)
- [HEIF format overview](https://nokiatech.github.io/heif/)

The historical link is retained for provenance; its availability and contents
have not been revalidated. It is not a substitute for the relevant specification.

## Implementation mapping and parity status

- Java: `Source/com/drew/metadata/heif/`.
- .NET: `MetadataExtractor/Formats/Heif/` and
  `MetadataExtractor/Formats/Iso14496/`.

Java-only behavior: item data (`Exif`, `mime` items) is located by `meta/iloc`
and read from `mdat` on the way through the file. When `mdat` precedes `meta`,
a `File` or `RandomAccessReader` input (`HeifMetadataReader.readMetadata(IsoInput)`,
`readMetadata(File)`, or `ImageMetadataReader.readMetadata(File)`) reads the
item data by position after the walk; an `InputStream` input keeps `mdat` in
memory until `meta` is read, up to `IsoBoxReaders.DEFAULT_MAX_BUFFERED_PAYLOAD`
(64 MiB), and reports the items as not read beyond that. With `meta` before
`mdat`, the usual order, nothing is buffered either way.

Java, deferred to a future major version (public API, so not changed in a minor
release):

- Remove `ItemInfoBox`, `ItemLocationBox`, `PrimaryItemBox` and
  `ItemProtectionBox` from `com.drew.metadata.heif.boxes`, deprecated since
  `IsoItems` reads `iinf` and `iloc`; move the `ItemLocationBoxTest` cases
  (`offset_size` or `length_size` of 0) to a test of `IsoItems` first.
- Remove or redirect `HeifBoxTypes`, `HeifContainerTypes` and `HeifItemTypes`:
  the path readers name boxes by path literal and nothing reads the constants.
- Move `Box` and `FullBox` from `com.drew.metadata.heif.boxes` to
  `com.drew.imaging.isobmff`, where `IsoBoxLayout`, `IsoStandardLayout` and the
  MP4 readers use them; Java has no re-export, so a deprecated subclass at the
  old name would keep only the old-to-new assignment direction working.

This document migrates a reference, not a verified feature inventory. Do not
infer equivalent item, property, Exif, XMP, thumbnail, or transformation support
from the shared format name. Compare the same sample bytes and the applicable
specification before recording a capability gap or claiming parity.
