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

package com.facebook.buck.java;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.common.io.Closer;
import com.google.common.io.Files;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

/**
 * Creates a JAR file from a collection of directories/ZIP/JAR files.
 */
public class JarDirectoryStep implements Step {

  /** Where to write the new JAR file. */
  private final String pathToOutputFile;

  /** A collection of directories/ZIP/JAR files to include in the generated JAR file. */
  private final ImmutableSet<String> entriesToJar;

  /** If specified, the Main-Class to list in the manifest of the generated JAR file. */
  @Nullable
  private final String mainClass;

  /** If specified, the Manifest file to use for the generated JAR file.  */
  @Nullable
  private final String manifestFile;

  /**
   * Creates a JAR from the specified entries (most often, classpath entries).
   * <p>
   * If an entry is a directory, then its files are traversed and added to the generated JAR.
   * <p>
   * If an entry is a file, then it is assumed to be a ZIP/JAR file, and its entries will be read
   * and copied to the generated JAR.
   * @param pathToOutputFile The directory that contains this path must exist before this command is
   *     executed.
   * @param entriesToJar Paths to directories/ZIP/JAR files.
   * @param mainClass If specified, the value for the Main-Class attribute in the manifest of the
   *     generated JAR.
   * @param manifestFile If specified, the path to the manifest file to use with this JAR.
   */
  public JarDirectoryStep(String pathToOutputFile,
                          Set<String> entriesToJar,
                          @Nullable String mainClass,
                          @Nullable String manifestFile) {
    this.pathToOutputFile = Preconditions.checkNotNull(pathToOutputFile);
    this.entriesToJar = ImmutableSet.copyOf(entriesToJar);
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;
  }

  private String getJarArgs() {
    String result = "cf";
    if (manifestFile != null) {
      result += "m";
    }
    return result;
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "jar " + getJarArgs();
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("jar %s %s %s %s",
        getJarArgs(),
        pathToOutputFile,
        manifestFile != null ? manifestFile : "",
        Joiner.on(' ').join(entriesToJar));
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      createJarFile(context);
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
    return 0;
  }

  private void createJarFile(ExecutionContext context) throws IOException {
    Manifest manifest = new Manifest();

    // Write the manifest, as appropriate.
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    if (manifestFile != null) {
      FileInputStream manifestStream = new FileInputStream(
          filesystem.getFileForRelativePath(manifestFile));
      boolean readSuccessfully = false;
      try {
        manifest.read(manifestStream);
        readSuccessfully = true;
      } finally {
        Closeables.close(manifestStream, !readSuccessfully);
      }

    } else {
      manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    }

    Closer closer = Closer.create();
    try {
      JarOutputStream outputFile = closer.register(new JarOutputStream(
          new BufferedOutputStream(new FileOutputStream(
              filesystem.getFileForRelativePath(pathToOutputFile)))));

      Set<String> directoryEntriesInsertedIntoOutputJar = Sets.newHashSet();
      ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
      for (String entry : entriesToJar) {
        File file = projectFilesystem.getFileForRelativePath(entry);
        if (file.isFile()) {
          // Assume the file is a ZIP/JAR file.
          copyZipEntriesToJar(file, outputFile, manifest, directoryEntriesInsertedIntoOutputJar);
        } else if (file.isDirectory()) {
          addFilesInDirectoryToJar(file, outputFile, directoryEntriesInsertedIntoOutputJar);
        } else {
          throw new IllegalStateException("Must be a file or directory: " + file);
        }
      }

      // The process of merging the manifests means that existing entries are
      // overwritten. To ensure that our main_class is set as expected, we
      // write it here.
      if (mainClass != null) {
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
      }

      JarEntry manifestEntry = new JarEntry(JarFile.MANIFEST_NAME);
      outputFile.putNextEntry(manifestEntry);
      manifest.write(outputFile);
    } finally {
      closer.close();
    }
  }

  /**
   * @param file is assumed to be a zip file.
   * @param jar is the file being written.
   * @param manifest that should get a copy of (@code jar}'s manifest entries.
   * @param directoryEntriesInsertedIntoOutputJar is used to avoid duplicate entries.
   */
  private void copyZipEntriesToJar(File file,
      final JarOutputStream jar,
      Manifest manifest,
      Set<String> directoryEntriesInsertedIntoOutputJar) throws IOException {
    ZipFile zip = new ZipFile(file);
    for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
      ZipEntry entry = entries.nextElement();
      String entryName = entry.getName();

      if (entryName.equals(JarFile.MANIFEST_NAME)) {
        Manifest readManifest = readManifest(zip, entry);
        merge(manifest, readManifest);
        continue;
      }

      // The same directory entry cannot be added more than once.
      if (directoryEntriesInsertedIntoOutputJar.contains(entryName)) {
        continue;
      }

      jar.putNextEntry(entry);
      InputStream inputStream = zip.getInputStream(entry);
      ByteStreams.copy(inputStream, jar);
      jar.closeEntry();

      if (entryName.endsWith("/")) {
        directoryEntriesInsertedIntoOutputJar.add(entryName);
      }
    }
  }

  private Manifest readManifest(ZipFile zip, ZipEntry manifestMfEntry) throws IOException {
    Closer closer = Closer.create();
    ByteArrayOutputStream output = closer.register(
        new ByteArrayOutputStream((int) manifestMfEntry.getSize()));
    InputStream stream = closer.register(zip.getInputStream(manifestMfEntry));
    try {
      ByteStreams.copy(stream, output);
      ByteArrayInputStream rawManifest = new ByteArrayInputStream(output.toByteArray());
      return new Manifest(rawManifest);
    } finally {
      closer.close();
    }
  }

  /**
   * @param directory that must not contain symlinks with loops.
   * @param jar is the file being written.
   */
  private void addFilesInDirectoryToJar(File directory,
      final JarOutputStream jar,
      final Set<String> directoryEntriesInsertedIntoOutputJar) {
    new DirectoryTraversal(directory) {

      @Override
      public void visit(File file, String relativePath) {
        JarEntry entry = new JarEntry(relativePath);
        String entryName = entry.getName();
        entry.setTime(file.lastModified());
        try {
          if (directoryEntriesInsertedIntoOutputJar.contains(entryName)) {
            return;
          }
          jar.putNextEntry(entry);
          Files.copy(file, jar);
          jar.closeEntry();

          if (entryName.endsWith("/")) {
            directoryEntriesInsertedIntoOutputJar.add(entryName);
          }
        } catch (IOException e) {
          Throwables.propagate(e);
        }
      }

    }.traverse();
  }

  /**
   * Merge entries from two Manifests together, with existing attributes being
   * overwritten.
   *
   * @param into The Manifest to modify.
   * @param from The Manifest to copy from.
   */
  private void merge(Manifest into, Manifest from) {
    Preconditions.checkNotNull(into);
    Preconditions.checkNotNull(from);

    Attributes attributes = from.getMainAttributes();
    if (attributes != null) {
      for (Map.Entry<Object, Object> attribute : attributes.entrySet()) {
        into.getMainAttributes().put(attribute.getKey(), attribute.getValue());
      }
    }

    Map<String, Attributes> entries = from.getEntries();
    if (entries != null) {
      for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
        into.getEntries().put(entry.getKey(), entry.getValue());
      }
    }
  }

}
