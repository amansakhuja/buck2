/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A specification used by the parser, via {@link TargetNodeSpec}, to match build files.
 */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractBuildFileSpec {

  // Base path where to find either a single build file or to recursively for many build files.
  @Value.Parameter
  abstract Path getBasePath();

  // If present, this indicates that the above path should be recursively searched for build files,
  // and that the paths enumerated here should be ignored.
  @Value.Parameter
  abstract boolean isRecursive();

  public static BuildFileSpec fromRecursivePath(Path basePath) {
    return BuildFileSpec.of(basePath, /* recursive */ true);
  }

  public static BuildFileSpec fromPath(Path basePath) {
    return BuildFileSpec.of(basePath, /* recursive */ false);
  }

  public static BuildFileSpec fromBuildTarget(BuildTarget target) {
    return fromPath(target.getBasePath());
  }

  /**
   * Find all build in the given {@link ProjectFilesystem}, and pass each to the given callable.
   */
  public void forEachBuildFile(
      final ProjectFilesystem filesystem,
      final String buildFileName,
      final Function<Path, Void> function)
      throws IOException {

    // If non-recursive, we just want the build file in the target spec's given base dir.
    if (!isRecursive()) {
      function.apply(filesystem.resolve(getBasePath().resolve(buildFileName)));
      return;
    }

    // Otherwise, we need to do a recursive walk to find relevant build files.
    filesystem.walkRelativeFileTree(
        getBasePath(),
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(
              Path dir,
              BasicFileAttributes attrs)
              throws IOException {
            // Skip sub-dirs that we should ignore.
            if (filesystem.isIgnored(dir)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(
              Path file,
              BasicFileAttributes attrs)
              throws IOException {
            if (buildFileName.equals(file.getFileName().toString()) &&
                !filesystem.isIgnored(file)) {
              function.apply(filesystem.resolve(file));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(
              Path file, IOException exc) throws IOException {
            throw exc;
          }

          @Override
          public FileVisitResult postVisitDirectory(
              Path dir,
              IOException exc)
              throws IOException {
            if (exc != null) {
              throw exc;
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * @return paths to build files that this spec match in the given {@link ProjectFilesystem}.
   */
  public ImmutableSet<Path> findBuildFiles(Cell cell) throws IOException {
    final ImmutableSet.Builder<Path> buildFiles = ImmutableSet.builder();

    forEachBuildFile(
        cell.getFilesystem(),
        cell.getBuildFileName(),
        new Function<Path, Void>() {
          @Override
          public Void apply(Path buildFile) {
            buildFiles.add(buildFile);
            return null;
          }
        });

    return buildFiles.build();
  }

}
