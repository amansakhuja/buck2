/*
 * Copyright 2017-present Facebook, Inc.
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

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Utility class for methods related to args handling. */
public class BuckArgsMethods {

  private static final ImmutableSet<String> FLAG_FILE_OPTIONS = ImmutableSet.of("-f", "--flagfile");

  private BuckArgsMethods() {
    // Utility class.
  }

  private static Stream<String> getArgsFromTextFile(Path argsPath) throws IOException {
    return Files.readAllLines(argsPath, Charsets.UTF_8).stream();
  }

  private static Stream<String> getArgsFromPythonFile(Path argsPath, String suffix)
      throws IOException {
    Process proc =
        Runtime.getRuntime()
            .exec(
                new String[] {"python", argsPath.toAbsolutePath().toString(), "--flavors", suffix});
    try (InputStream input = proc.getInputStream();
        OutputStream output = proc.getOutputStream();
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(input, Charsets.UTF_8)); ) {
      return reader.lines().collect(Collectors.toList()).stream();
    }
  }

  private static Stream<String> getArgsFromPath(Path argsPath, Optional<String> flavors)
      throws IOException {
    if (!argsPath.toAbsolutePath().toString().endsWith(".py")) {
      if (flavors.isPresent()) {
        throw new HumanReadableException(
            "Flavors can only be used with python scripts that will output a config. If the file you "
                + "specified is a python script, please make sure the filename ends with .py.");
      }
      return getArgsFromTextFile(argsPath);
    }
    return getArgsFromPythonFile(argsPath, flavors.orElse(""));
  }

  /**
   * Expand AT-file syntax in a way that matches what args4j does. We have this because we'd like to
   * correctly log the arguments coming from the AT-files and there is no way to get the expanded
   * args array from args4j.
   *
   * <p>In addition to files passed using a regular {@code @} syntax, this method also extracts
   * command line arguments from AT-file syntax files passed via {@code -f} or {@code --flagfile}
   * command line option.
   *
   * @param args original args array
   * @param projectRoot path against which any {@code @args} path arguments will be resolved.
   * @return args array with AT-files expanded.
   */
  public static ImmutableList<String> expandAtFiles(Iterable<String> args, Path projectRoot) {
    Iterator<String> argsIterator = args.iterator();
    Stream<? extends String> argumentStream = Stream.empty();
    while (argsIterator.hasNext()) {
      String arg = argsIterator.next();
      if (FLAG_FILE_OPTIONS.contains(arg)) {
        if (!argsIterator.hasNext()) {
          throw new HumanReadableException(arg + " should be followed by a path.");
        }
        argumentStream =
            Stream.concat(argumentStream, expandFile(argsIterator.next(), projectRoot));
      } else if (arg.startsWith("@")) {
        argumentStream = Stream.concat(argumentStream, expandFile(arg.substring(1), projectRoot));
      } else {
        argumentStream = Stream.concat(argumentStream, ImmutableList.of(arg).stream());
      }
    }
    return argumentStream.collect(MoreCollectors.toImmutableList());
  }

  /** Extracts command line options from a file identified by {@code arg} with AT-file syntax. */
  private static Stream<? extends String> expandFile(String arg, Path projectRoot) {
    String[] parts = arg.split("#", 2);
    String unresolvedArgsPath = parts[0];
    Path argsPath = projectRoot.resolve(Paths.get(unresolvedArgsPath));

    if (!Files.exists(argsPath)) {
      throw new HumanReadableException(
          "The file "
              + unresolvedArgsPath
              + " can't be found. Please make sure the path exists relatively to the "
              + "current folder.");
    }
    Optional<String> flavors = parts.length == 2 ? Optional.of(parts[1]) : Optional.empty();
    try {
      return getArgsFromPath(argsPath, flavors);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Could not read options from " + arg);
    }
  }

  /**
   * Drops options from the args array.
   *
   * @param args args array.
   * @param optionsToSkip if args contains an element from this array skip the element and the
   *     element immediately after it.
   * @return filtered args array
   */
  public static ImmutableList<String> filterArgs(
      List<String> args, ImmutableSet<String> optionsToSkip) {
    ImmutableList.Builder<String> result = ImmutableList.builder();
    for (int i = 0; i < args.size(); ++i) {
      if (optionsToSkip.contains(args.get(i))) {
        i += 1;
      } else {
        result.add(args.get(i));
      }
    }
    return result.build();
  }
}
