package com.facebook.buck.apple.clang;

import com.facebook.buck.model.Pair;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import java.io.IOException;
import java.nio.file.Path;

/**
 * VFSOverlays are used for similar purposes to headermaps, but can be used to overlay more than
 * headers on the filesystem (such as modulemaps)
 *
 * <p>This class provides support for reading and generating clang vfs overlays. No spec is
 * available but we conform to the https://clang.llvm.org/doxygen/VirtualFileSystem_8cpp_source.html
 * writer class defined in the Clang documentation.
 */
@JsonSerialize(as = VFSOverlay.class)
public class VFSOverlay {

  @SuppressWarnings("PMD.UnusedPrivateField")
  @JsonProperty
  private final int version = 0;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @JsonProperty("case-sensitive")
  private final boolean case_sensitive = false;

  @JsonProperty("roots")
  private ImmutableList<VirtualDirectory> computeRoots() {
    Multimap<Path, Pair<Path, Path>> byParent = MultimapBuilder.hashKeys().hashSetValues().build();
    overlays.forEach(
        (virtual, real) -> {
          byParent.put(virtual.getParent(), new Pair<>(virtual.getFileName(), real));
        });
    return byParent
        .asMap()
        .entrySet()
        .stream()
        .map(
            e ->
                new VirtualDirectory(
                    e.getKey(),
                    e.getValue()
                        .stream()
                        .map(x -> new VirtualFile(x.getFirst(), x.getSecond()))
                        .collect(MoreCollectors.toImmutableList())))
        .collect(MoreCollectors.toImmutableList());
  }

  private final ImmutableSortedMap<Path, Path> overlays;

  public VFSOverlay(ImmutableSortedMap<Path, Path> overlays) {
    this.overlays = overlays;
  }

  public String render() throws IOException {
    return ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValueAsString(this);
  }

  @JsonSerialize(as = VirtualDirectory.class)
  private class VirtualDirectory {

    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonProperty
    private final String type = "directory";

    @JsonProperty private final Path name;

    @JsonProperty("contents")
    private final ImmutableList<VirtualFile> fileList;

    public VirtualDirectory(Path root, ImmutableList<VirtualFile> fileList) {
      this.name = root;
      this.fileList = fileList;
    }
  }

  @JsonSerialize(as = VirtualFile.class)
  private class VirtualFile {
    @SuppressWarnings("PMD.UnusedPrivateField")
    @JsonProperty
    private final String type = "file";

    @JsonProperty private final Path name;

    @JsonProperty("external-contents")
    private final Path realPath;

    public VirtualFile(Path name, Path realPath) {
      this.name = name;
      this.realPath = realPath;
      if (!realPath.isAbsolute()) {
        throw new HumanReadableException(
            "Attempting to make vfsoverlay with non-absolute path '%s' for external contents "
                + "field. Only absolute paths are currently supported.",
            realPath);
      }
    }
  }
}
