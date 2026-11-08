/**
 * Contains classes for the extraction and modelling of MOV file format metadata.
 * See <a href="https://github.com/drewnoakes/metadata-extractor/blob/main/docs/formats/QUICKTIME.md">QuickTime format documentation</a>
 * for atom structure, handler context, and references.
 * <p>
 * Deprecated, except for {@link com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory}:
 * {@link com.drew.imaging.mp4.Mp4MetadataReader} reads QuickTime files into the directories of
 * {@link com.drew.metadata.mp4}, where new tags are added. These directories keep the tags they
 * have, and {@link com.drew.imaging.quicktime.QuickTimeMetadataReader} keeps filling them until
 * the next major version.
 */
package com.drew.metadata.mov;
