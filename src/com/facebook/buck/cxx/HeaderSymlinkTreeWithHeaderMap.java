/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;

public final class HeaderSymlinkTreeWithHeaderMap extends HeaderSymlinkTree {

  private static final Logger LOG = Logger.get(HeaderSymlinkTreeWithHeaderMap.class);

  @AddToRuleKey(stringify = true)
  private final Path headerMapPath;

  private HeaderSymlinkTreeWithHeaderMap(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links,
      Path headerMapPath) {
    super(target, filesystem, root, links);
    this.headerMapPath = headerMapPath;
  }

  public static HeaderSymlinkTreeWithHeaderMap create(
      BuildTarget target,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMap<Path, SourcePath> links) {
    Path headerMapPath = getPath(filesystem, target);
    return new HeaderSymlinkTreeWithHeaderMap(target, filesystem, root, links, headerMapPath);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), headerMapPath);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    LOG.debug("Generating post-build steps to write header map to %s", headerMapPath);
    Path buckOut =
        getProjectFilesystem().resolve(getProjectFilesystem().getBuckPaths().getBuckOut());

    ImmutableMap.Builder<Path, Path> headerMapEntries = ImmutableMap.builder();
    for (Path key : getLinks().keySet()) {
      // The key is the path that will be referred to in headers. It can be anything. However, the
      // value given in the headerMapEntries is the path of that entry in the generated symlink
      // tree. Because "reasons", we don't want to cache that value, so we need to relativize the
      // path to the output directory of this current rule. We then rely on magic and the stars
      // aligning in order to get this to work. May we find peace in another life.
      headerMapEntries.put(key, buckOut.relativize(getRoot().resolve(key)));
    }
    ImmutableList.Builder<Step> builder =
        ImmutableList.<Step>builder()
            .addAll(super.getBuildSteps(context, buildableContext))
            .add(
                new HeaderMapStep(getProjectFilesystem(), headerMapPath, headerMapEntries.build()));
    return builder.build();
  }

  @Override
  public Path getIncludePath() {
    return getProjectFilesystem().resolve(getProjectFilesystem().getBuckPaths().getBuckOut());
  }

  @Override
  public Optional<Path> getHeaderMap() {
    return Optional.of(getProjectFilesystem().resolve(headerMapPath));
  }

  @VisibleForTesting
  static Path getPath(ProjectFilesystem filesystem, BuildTarget target) {
    return BuildTargets.getGenPath(filesystem, target, "%s.hmap");
  }
}
