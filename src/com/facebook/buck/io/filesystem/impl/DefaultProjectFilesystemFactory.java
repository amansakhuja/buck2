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

package com.facebook.buck.io.filesystem.impl;

import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.PathOrGlobMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.config.Config;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public class DefaultProjectFilesystemFactory implements ProjectFilesystemFactory {

  @VisibleForTesting public static final String BUCK_BUCKD_DIR_KEY = "buck.buckd_dir";

  // A non-exhaustive list of characters that might indicate that we're about to deal with a glob.
  private static final Pattern GLOB_CHARS = Pattern.compile("[\\*\\?\\{\\[]");

  @Override
  public ProjectFilesystem createProjectFilesystem(Path root, Config config)
      throws InterruptedException {
    return new DefaultProjectFilesystem(
        root.getFileSystem(),
        root,
        extractIgnorePaths(root, config, getConfiguredBuckPaths(root, config)),
        getConfiguredBuckPaths(root, config),
        ProjectFilesystemDelegateFactory.newInstance(
            root,
            getConfiguredBuckPaths(root, config).getBuckOut(),
            config.getValue("version_control", "hg_cmd").orElse("hg"),
            config),
        config.getBooleanValue("project", "windows_symlinks", false));
  }

  @Override
  public ProjectFilesystem createProjectFilesystem(Path root) throws InterruptedException {
    return createProjectFilesystem(root, new Config());
  }

  private static ImmutableSet<PathOrGlobMatcher> extractIgnorePaths(
      final Path root, Config config, final BuckPaths buckPaths) {
    ImmutableSet.Builder<PathOrGlobMatcher> builder = ImmutableSet.builder();

    builder.add(new PathOrGlobMatcher(root, ".idea"));

    final String projectKey = "project";
    final String ignoreKey = "ignore";

    String buckdDirProperty = System.getProperty(BUCK_BUCKD_DIR_KEY, ".buckd");
    if (!Strings.isNullOrEmpty(buckdDirProperty)) {
      builder.add(new PathOrGlobMatcher(root, buckdDirProperty));
    }

    Path cacheDir =
        DefaultProjectFilesystem.getCacheDir(root, config.getValue("cache", "dir"), buckPaths);
    builder.add(new PathOrGlobMatcher(cacheDir));

    builder.addAll(
        FluentIterable.from(config.getListWithoutComments(projectKey, ignoreKey))
            .transform(
                new Function<String, PathOrGlobMatcher>() {
                  @Nullable
                  @Override
                  public PathOrGlobMatcher apply(String input) {
                    // We don't really want to ignore the output directory when doing things like filesystem
                    // walks, so return null
                    if (buckPaths.getBuckOut().toString().equals(input)) {
                      return null; //root.getFileSystem().getPathMatcher("glob:**");
                    }

                    if (GLOB_CHARS.matcher(input).find()) {
                      return new PathOrGlobMatcher(
                          root.getFileSystem().getPathMatcher("glob:" + input), input);
                    }
                    return new PathOrGlobMatcher(root, input);
                  }
                })
            // And now remove any null patterns
            .filter(Objects::nonNull)
            .toList());

    return builder.build();
  }

  public static BuckPaths getConfiguredBuckPaths(Path rootPath, Config config) {
    BuckPaths buckPaths = BuckPaths.createDefaultBuckPaths(rootPath);
    Optional<String> configuredBuckOut = config.getValue("project", "buck_out");
    if (configuredBuckOut.isPresent()) {
      buckPaths =
          buckPaths.withConfiguredBuckOut(
              rootPath.getFileSystem().getPath(configuredBuckOut.get()));
    }
    return buckPaths;
  }

  @Override
  public ProjectFilesystem createOrThrow(Path path) throws InterruptedException {
    try {
      // toRealPath() is necessary to resolve symlinks, allowing us to later
      // check whether files are inside or outside of the project without issue.
      return createProjectFilesystem(path.toRealPath().normalize());
    } catch (IOException e) {
      throw new HumanReadableException(
          String.format(
              ("Failed to resolve project root [%s]."
                  + "Check if it exists and has the right permissions."),
              path.toAbsolutePath()),
          e);
    }
  }
}
