/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.google.common.io.Closeables;
import com.google.common.io.Files;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MoreFiles {

  private final static Logger logger = Logger.getLogger(MoreFiles.class.getName());

  private static final Function<Path, Path> IDENTITY_TRANSFORM = new Function<Path, Path>() {
    @Override
    public Path apply(Path path) {
      return path;
    }
  };

  /** Utility class: do not instantiate. */
  private MoreFiles() {}

  public static void rmdir(String path, ProcessExecutor processExecutor) throws IOException {
    // Unfortunately, Guava's Files.deleteRecursively() method is deprecated.
    // This is what the deprecation message suggested to do instead.
    // Unfortunately, it is not cross-platform.
    Process process = Runtime.getRuntime().exec(new String[] {"rm", "-rf", path});
    processExecutor.execute(process);
  }

  /**
   * Recursively copies all files under {@code fromPath} to {@code toPath}.
   */
  public static void copyRecursively(
      final Path fromPath,
      final Path toPath) throws IOException {
    copyRecursively(fromPath, toPath, IDENTITY_TRANSFORM);
  }

  /**
   * Recursively copies all files under {@code fromPath} to {@code toPath}.
   * The {@code transform} will be applied after the destination path for a file has been
   * relativized.
   * @param fromPath item to copy
   * @param toPath destination of copy
   * @param transform renaming function to apply when copying. If this function returns null, then
   *     the file is not copied.
   */
  public static void copyRecursively(
      final Path fromPath,
      final Path toPath,
      final Function<Path, Path> transform) throws IOException {
    // Adapted from http://codingjunkie.net/java-7-copy-move/.
    SimpleFileVisitor<Path> copyDirVisitor = new SimpleFileVisitor<Path>() {

      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        Path targetPath = toPath.resolve(fromPath.relativize(dir));
        if (!java.nio.file.Files.exists(targetPath)) {
          java.nio.file.Files.createDirectory(targetPath);
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Path destPath = toPath.resolve(fromPath.relativize(file));
        Path transformedDestPath = transform.apply(destPath);
        if (transformedDestPath != null) {
          java.nio.file.Files.copy(file, transformedDestPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return FileVisitResult.CONTINUE;
      }
    };
    java.nio.file.Files.walkFileTree(fromPath, copyDirVisitor);
  }

  public static void deleteRecursively(final Path path) throws IOException {
    // Adapted from http://codingjunkie.net/java-7-copy-move/.
    SimpleFileVisitor<Path> deleteDirVisitor = new SimpleFileVisitor<Path>() {

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        java.nio.file.Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
        if (e == null) {
          java.nio.file.Files.delete(dir);
          return FileVisitResult.CONTINUE;
        } else {
          throw e;
        }
      }
    };
    java.nio.file.Files.walkFileTree(path, deleteDirVisitor);
  }

  /**
   * Writes the specified lines to the specified file, encoded as UTF-8.
   */
  public static void writeLinesToFile(Iterable<String> lines, String pathToFile)
      throws IOException {
    BufferedWriter writer = null;
    try {
      writer = new BufferedWriter(
          new OutputStreamWriter(
              new FileOutputStream(pathToFile),
              Charsets.UTF_8));
      for (String line : lines) {
        writer.write(line);
        writer.newLine();
      }
    } finally {
      Closeables.close(writer, false /* swallowIOException */);
    }
  }

  /**
   * Checks whether the lines in the specified file (encoded as UTF-8) match those in the specified
   * Iterable.
   */
  @SuppressWarnings("resource") // Closeables.close(...)
  public static boolean isMatchingFileContents(Iterable<String> lines, File file)
      throws IOException {
    boolean matching = true;
    BufferedReader reader = null;
    Iterator<String> iter = lines.iterator();
    try {
      reader = Files.newReader(file, Charsets.UTF_8);
      while (iter.hasNext()) {
        String line = reader.readLine();
        if (!Objects.equal(iter.next(), line)) {
          matching = false;
          break;
        }
      }

      if (matching) {
        // At this point, the Iterator has been exhausted, and all lines read so far match what is in
        // the Iterator. However, if the reader still contains more lines, then the two are not equal.
        matching = reader.readLine() == null;
      }
    } finally {
      Closeables.close(reader, false /* swallowIOException */);
    }

    if (!matching && logger.isLoggable(Level.INFO)) {
      logger.info(String.format("DIFF %s", file.getPath()));
      for (String diffLine : diffFileContents(lines, file)) {
        logger.info(String.format("DIFF %s", diffLine));
      }
    }

    return matching;
  }

  /**
   * Log a simplistic diff between lines and the contents of file.
   */
  @VisibleForTesting
  static List<String> diffFileContents(Iterable<String> lines, File file) throws IOException {
    List<String> diffLines = Lists.newArrayList();
    BufferedReader reader = null;
    Iterator<String> iter = lines.iterator();
    try {
      reader = Files.newReader(file, Charsets.UTF_8);
      while (iter.hasNext()) {
        String lineA = reader.readLine();
        String lineB = iter.next();
        if (!Objects.equal(lineA, lineB)) {
          diffLines.add(String.format("| %s | %s |", lineA == null ? "" : lineA, lineB));
        }
      }

      String lineA;
      while ((lineA = reader.readLine()) != null) {
        diffLines.add(String.format("| %s |  |", lineA));
      }
    } finally {
      Closeables.close(reader, false /* swallowIOException */);
    }
    return diffLines;
  }
}
