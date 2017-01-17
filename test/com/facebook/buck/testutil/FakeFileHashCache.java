/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.testutil;

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * FileHashCache that is populated with a fixed set of entries.
 */
public class FakeFileHashCache implements FileHashCache {

  private final Map<Path, HashCode> pathsToHashes;
  private final Map<ArchiveMemberPath, HashCode> archiveMemberPathsToHashes;
  private final Map<Path, Long> pathsToSizes;

  public static FakeFileHashCache withArchiveMemberPathHashes(
      Map<ArchiveMemberPath, HashCode> archiveMemberPathsToHashes) {
    return new FakeFileHashCache(
        new HashMap<Path, HashCode>(),
        archiveMemberPathsToHashes,
        new HashMap<Path, Long>());
  }

  public FakeFileHashCache(Map<Path, HashCode> pathsToHashes) {
    this(pathsToHashes, new HashMap<ArchiveMemberPath, HashCode>(), new HashMap<Path, Long>());
  }

  public FakeFileHashCache(
      Map<Path, HashCode> pathsToHashes,
      Map<ArchiveMemberPath, HashCode> archiveMemberPathsToHashes,
      Map<Path, Long> pathsToSizes) {
    this.archiveMemberPathsToHashes = archiveMemberPathsToHashes;
    this.pathsToHashes = pathsToHashes;
    this.pathsToSizes = pathsToSizes;
  }

  public static FakeFileHashCache createFromStrings(Map<String, String> pathsToHashes) {
    return createFromStrings(new FakeProjectFilesystem(), pathsToHashes);
  }

  public static FakeFileHashCache createFromStrings(
      ProjectFilesystem filesystem,
      Map<String, String> pathsToHashes) {
    Map<Path, HashCode> cachedValues = new HashMap<>();
    for (Map.Entry<String, String> entry : pathsToHashes.entrySet()) {
      // Retain the original behaviour
      cachedValues.put(Paths.get(entry.getKey()), HashCode.fromString(entry.getValue()));

      // And ensure that the absolute path is also present.
      if (!entry.getKey().startsWith("/")) {
        cachedValues.put(filesystem.resolve(entry.getKey()), HashCode.fromString(entry.getValue()));
      }
    }
    return new FakeFileHashCache(cachedValues);
  }

  @Override
  public boolean willGet(Path path) {
    return true;
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberPath) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public void invalidate(Path path) {
    pathsToHashes.remove(path);
  }

  @Override
  public void invalidateAll() {
    pathsToHashes.clear();
  }

  @Override
  public HashCode get(Path path) throws IOException {
    HashCode hashCode = pathsToHashes.get(path);
    if (hashCode == null) {
      throw new NoSuchFileException(path.toString());
    }
    return hashCode;
  }

  @Override
  public long getSize(Path path) throws IOException {
    Long hashCode = pathsToSizes.get(path);
    if (hashCode == null) {
      throw new NoSuchFileException(path.toString());
    }
    return hashCode;
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
    HashCode hashCode = archiveMemberPathsToHashes.get(archiveMemberPath);
    if (hashCode == null) {
      throw new NoSuchFileException(archiveMemberPath.toString());
    }
    return hashCode;
  }

  @Override
  public void set(Path path, HashCode hashCode) {
    pathsToHashes.put(path, hashCode);
  }

  public boolean contains(Path path) {
    return pathsToHashes.containsKey(path);
  }

}
