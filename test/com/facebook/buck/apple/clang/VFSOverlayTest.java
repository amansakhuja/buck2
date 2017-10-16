package com.facebook.buck.apple.clang;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Test;

public class VFSOverlayTest {

  private String readTestData(String name) throws IOException {
    return new String(ByteStreams.toByteArray(getClass().getResourceAsStream(name)));
  }

  @Test
  public void testSerialization() throws IOException {
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                Paths.get("/virtual/path/module.modulemap"),
                Paths.get("/real/path/overlayed.modulemap")));
    assertEquals(readTestData("testdata/vfs_simple.yaml"), vfsOverlay.render());
  }

  @Test
  public void testTwoFiles() throws IOException {
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                Paths.get("/virtual/path/module.modulemap"),
                Paths.get("/real/path/overlayed.modulemap"),
                Paths.get("/virtual/path/umbrella.h"),
                Paths.get("/real/path/umbrella/umbrella.h")));

    assertEquals(readTestData("testdata/vfs_twofiles.yaml"), vfsOverlay.render());
  }

  @Test
  public void testTwoDirectories() throws IOException {
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                Paths.get("/virtual/path/module.modulemap"),
                Paths.get("/real/path/overlayed.modulemap"),
                Paths.get("/virtual/path-priv/umbrella.h"),
                Paths.get("/real/path/umbrella/umbrella.h")));
    assertEquals(readTestData("testdata/vfs_twodirs.yaml"), vfsOverlay.render());
  }

  @Test
  public void testNestedDirectories() throws IOException {
    // the default clang writer groups nested directories, for simplicity this generator doesn't.
    // This test shows the expectation for this generator, we can update it if we implement
    // directory nesting/grouping. Clang has an internal representation after reading the vfs so as
    // long as the contents of the vfs are correct the layout is not relevant for speed/correctness.
    VFSOverlay vfsOverlay =
        new VFSOverlay(
            ImmutableSortedMap.of(
                Paths.get("/virtual/module.modulemap"),
                Paths.get("/real/path/overlayed.modulemap"),
                Paths.get("/virtual/path/priv/umbrella.h"),
                Paths.get("/real/path/umbrella/umbrella.h"),
                Paths.get("/virtual/path/priv/entry.h"),
                Paths.get("/real/path/umbrella/lib/entry.h")));
    assertEquals(readTestData("testdata/vfs_nesteddirs.yaml"), vfsOverlay.render());
  }
}
