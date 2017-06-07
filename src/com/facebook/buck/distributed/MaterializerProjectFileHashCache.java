/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.FileHashCacheVerificationResult;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class MaterializerProjectFileHashCache implements ProjectFileHashCache {

  private static final Logger LOG = Logger.get(MaterializerProjectFileHashCache.class);

  private final ImmutableMap<Path, BuildJobStateFileHashEntry> remoteFileHashesByAbsPath;
  private final Set<Path> symlinkedPaths;
  private final Set<Path> materializedPaths;
  private final FileContentsProvider provider;
  private final ProjectFilesystem projectFilesystem;
  private final ProjectFileHashCache delegate;

  public MaterializerProjectFileHashCache(
      ProjectFileHashCache delegate,
      BuildJobStateFileHashes remoteFileHashes,
      FileContentsProvider provider) {
    this.delegate = delegate;
    this.remoteFileHashesByAbsPath =
        DistBuildFileHashes.indexEntriesByPath(delegate.getFilesystem(), remoteFileHashes);
    this.symlinkedPaths = Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());
    this.materializedPaths = Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());
    this.provider = provider;
    this.projectFilesystem = delegate.getFilesystem();
  }

  /**
   * This method creates all symlinks and touches all regular files so that any file existence
   * checks during action graph transformation go through (for instance,
   * PrebuiltCxxLibraryDescription::requireSharedLibrary). Note: THIS IS A HACK. And this needs to
   * be here until the misbehaving rules are fixed.
   */
  public void preloadAllFiles() throws IOException {
    for (Path absPath : remoteFileHashesByAbsPath.keySet()) {
      LOG.info("Preloading: [%s]", absPath.toString());
      BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByAbsPath.get(absPath);
      if (fileHashEntry == null || fileHashEntry.isPathIsAbsolute()) {
        continue;
      } else if (fileHashEntry.isSetMaterializeDuringPreloading()
          && fileHashEntry.isMaterializeDuringPreloading()) {
        Path relPath = projectFilesystem.getPathRelativeToProjectRoot(absPath).get();
        get(relPath);
      } else if (fileHashEntry.isSetRootSymLink()) {
        materializeSymlink(fileHashEntry, symlinkedPaths);
        integrityCheck(getFilesystem().getPathRelativeToProjectRoot(absPath).get());
        symlinkedPaths.add(absPath);
      } else if (!fileHashEntry.isDirectory) {
        // Touch file
        projectFilesystem.createParentDirs(absPath);
        projectFilesystem.touch(absPath);
      } else {
        // Create directory
        // No need to materialize sub-dirs/files here, as there will be separate entries for those.
        projectFilesystem.mkdirs(absPath);
      }
    }
  }

  private void materializeIfNeeded(Path relPath, Queue<Path> remainingRelPaths) throws IOException {
    if (materializedPaths.contains(relPath)) {
      return;
    }

    LOG.info("Materializing path: [%s]", relPath.toString());

    Path absPath = projectFilesystem.resolve(relPath).toAbsolutePath();
    BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByAbsPath.get(absPath);
    if (fileHashEntry == null || fileHashEntry.isPathIsAbsolute()) {
      recordMaterializedPath(relPath);
      return;
    }

    if (fileHashEntry.isSetRootSymLink()) {
      if (!symlinkedPaths.contains(relPath)) {
        materializeSymlink(fileHashEntry, materializedPaths);
      }
      recordMaterializedPath(relPath);
      return;
    }

    if (fileHashEntry.isIsDirectory()) {
      materializeDirectory(relPath, fileHashEntry, remainingRelPaths);
      materializedPaths.add(relPath);
      return;
    }

    materializeFile(relPath, fileHashEntry);
  }

  private void materializeFile(Path relPath, BuildJobStateFileHashEntry fileHashEntry)
      throws IOException {
    Path absPath = projectFilesystem.resolve(relPath).toAbsolutePath();
    projectFilesystem.createParentDirs(projectFilesystem.resolve(relPath));

    // We should materialize files using multiple threads, but we need to maintain synchronization
    // on at least a per-file basis. Trying to materialize a file again might make the file seem
    // unavailable for a small window when we're writing it again.
    synchronized (fileHashEntry) {
      // Check if someone materialized the file while we were waiting for synchronization.
      if (materializedPaths.contains(relPath)) {
        return;
      }

      Preconditions.checkState(
          provider.materializeFileContents(fileHashEntry, absPath),
          "Failed to materialize source file [%s] for FileHashEntry=[%s].",
          absPath,
          fileHashEntry);

      absPath.toFile().setExecutable(fileHashEntry.isExecutable);
      recordMaterializedPath(relPath);
    }
  }

  private synchronized void materializeDirectory(
      Path path, BuildJobStateFileHashEntry fileHashEntry, Queue<Path> remainingPaths)
      throws IOException {
    if (materializedPaths.contains(path)) {
      return;
    }

    projectFilesystem.mkdirs(path);

    for (PathWithUnixSeparators unixPath : fileHashEntry.getChildren()) {
      Path absPath = projectFilesystem.resolve(Paths.get(unixPath.getPath()));
      Path relPath = projectFilesystem.getPathRelativeToProjectRoot(absPath).get();
      remainingPaths.add(relPath);
    }
  }

  private synchronized void materializeSymlink(
      BuildJobStateFileHashEntry fileHashEntry, Set<Path> processedPaths) {
    Path rootSymlink = projectFilesystem.resolve(fileHashEntry.getRootSymLink().getPath());

    if (symlinkedPaths.contains(rootSymlink)) {
      processedPaths.add(rootSymlink);
    }

    if (processedPaths.contains(rootSymlink)) {
      return;
    }
    processedPaths.add(rootSymlink);

    if (!projectFilesystem.getPathRelativeToProjectRoot(rootSymlink).isPresent()) {
      // RecordingProjectFileHashCache stored an absolute path (which was also a sym link).
      throw new RuntimeException(
          "Root symlink is not in project root: " + rootSymlink.toAbsolutePath());
    }

    Path rootSymlinkTarget =
        projectFilesystem.resolve(fileHashEntry.getRootSymLinkTarget().getPath());
    LOG.info(
        "Materializing sym link [%s] with target [%s]",
        rootSymlink.toAbsolutePath().toString(), rootSymlinkTarget.toAbsolutePath().toString());

    try {
      projectFilesystem.createParentDirs(rootSymlink);
      projectFilesystem.createSymLink(rootSymlink, rootSymlinkTarget, true /* force creation */);
    } catch (IOException e) {
      LOG.error(e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Verification of FileHashes is important because we anyways use the remote file hashes to
   * compute the RuleKeys (see {@link DistBuildCachingEngineDelegate}). We don't want to upload
   * corrupt cache artifacts in case we end up materializing corrupt source files.
   */
  private void integrityCheck(Path relPath) throws IOException {
    Path absPath = projectFilesystem.resolve(relPath).toAbsolutePath();
    BuildJobStateFileHashEntry fileHashEntry = remoteFileHashesByAbsPath.get(absPath);
    if (fileHashEntry == null || !fileHashEntry.isSetHashCode()) {
      return;
    }

    HashCode computedHash =
        Preconditions.checkNotNull(
            delegate.get(relPath),
            "File materialization failed. Delegate FileHashCache returned null HashCode for [%s].",
            relPath);
    if (!computedHash.toString().equals(fileHashEntry.getHashCode())) {
      throw new HumanReadableException(
          "SHA1 of materialized file (at [%s]) does not match the SHA1 sent by buck client.\n"
              + "Computed SHA1: %s\n"
              + "Expected SHA1: %s",
          relPath, computedHash.toString(), fileHashEntry.getHashCode());
    }
  }

  private void recordMaterializedPath(Path relPath) throws IOException {
    integrityCheck(relPath);
    materializedPaths.add(relPath);
  }

  @Override
  public HashCode get(Path relPath) throws IOException {
    Queue<Path> remainingPaths = new LinkedList<>();
    remainingPaths.add(relPath);
    while (remainingPaths.size() > 0) {
      materializeIfNeeded(remainingPaths.remove(), remainingPaths);
    }

    return delegate.get(relPath);
  }

  @Override
  public long getSize(Path relPath) throws IOException {
    return delegate.getSize(relPath);
  }

  @Override
  public HashCode get(ArchiveMemberPath archiveMemberRelPath) throws IOException {
    materializeIfNeeded(archiveMemberRelPath.getArchivePath(), new LinkedList<>());
    return delegate.get(archiveMemberRelPath);
  }

  @Override
  public ProjectFilesystem getFilesystem() {
    return projectFilesystem;
  }

  @Override
  public boolean willGet(Path relPath) {
    // DistBuildCellIndex makes sure only relative paths to the materializer's filesystem are
    // passed here so we can safely accept all paths here.
    return true;
  }

  @Override
  public boolean willGet(ArchiveMemberPath archiveMemberRelPath) {
    // DistBuildCellIndex makes sure only relative paths to the materializer's filesystem are
    // passed here so we can safely accept all paths here.
    return true;
  }

  @Override
  public void invalidate(Path relPath) {
    delegate.invalidate(relPath);
  }

  @Override
  public void invalidateAll() {
    delegate.invalidateAll();
  }

  @Override
  public void set(Path relPath, HashCode hashCode) throws IOException {
    delegate.set(relPath, hashCode);
  }

  @Override
  public FileHashCacheVerificationResult verify() throws IOException {
    return delegate.verify();
  }
}
