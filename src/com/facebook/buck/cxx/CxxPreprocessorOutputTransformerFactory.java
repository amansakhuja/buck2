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

package com.facebook.buck.cxx;

import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.LineProcessorThread;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class CxxPreprocessorOutputTransformerFactory {

  private final Path workingDir;
  private final ImmutableMap<Path, Path> replacementPaths;
  private final DebugPathSanitizer sanitizer;
  private final Optional<Function<String, Iterable<String>>> extraPreprocessorTransformer;

  public CxxPreprocessorOutputTransformerFactory(
      Path workingDir,
      Map<Path, Path> replacementPaths,
      DebugPathSanitizer sanitizer,
      Optional<Function<String, Iterable<String>>> extraPreprocessorTransformer) {
    this.workingDir = workingDir;
    this.replacementPaths = ImmutableMap.copyOf(replacementPaths);
    this.sanitizer = sanitizer;
    this.extraPreprocessorTransformer = extraPreprocessorTransformer;
  }

  public LineProcessorThread createTransformerThread(
      InputStream inputStream,
      OutputStream outputStream) {
    return new LineProcessorThread(inputStream, outputStream) {
      @Override
      public Iterable<String> process(String line) {
        return transformLine(line);
      }
    };
  }

  // N.B. These include paths are special to GCC. They aren't real files and there is no remapping
  // needed, so we can just ignore them everywhere.
  private static final ImmutableSet<String> SPECIAL_INCLUDE_PATHS = ImmutableSet.of(
      "<built-in>",
      "<command-line>"
  );

  private static final Pattern LINE_MARKERS =
      Pattern.compile("^# (?<num>\\d+) \"(?<path>[^\"]+)\"(?<rest>.*)?$");

  @VisibleForTesting
  Iterable<String> transformLine(String line) {
    if (line.startsWith("# ")) {
      return ImmutableList.of(transformPreprocessorLine(line));
    } else if (extraPreprocessorTransformer.isPresent()) {
      return extraPreprocessorTransformer.get().apply(line);
    } else {
      return ImmutableList.of(line);
    }
  }

  private String transformPreprocessorLine(String line) {
    Matcher m = LINE_MARKERS.matcher(line);

    if (m.find() && !SPECIAL_INCLUDE_PATHS.contains(m.group("path"))) {
      String originalPath = m.group("path");
      String replacementPath = originalPath;

      replacementPath = Optional
          .fromNullable(replacementPaths.get(Paths.get(replacementPath)))
          .transform(Escaper.PATH_FOR_C_INCLUDE_STRING_ESCAPER)
          .or(replacementPath);

      replacementPath = sanitizer.sanitize(Optional.of(workingDir), replacementPath);

      if (!originalPath.equals(replacementPath)) {
        String num = m.group("num");
        String rest = m.group("rest");
        return "# " + num + " \"" + replacementPath + "\"" + rest;
      }
    }

    return line;
  }
}
