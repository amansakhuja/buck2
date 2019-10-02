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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetpattern.ImmutableBuildTargetPattern;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Matches all {@link TargetNode} objects in a repository that match the specification. */
@Value.Immutable(builder = false)
public abstract class TargetNodePredicateSpec implements TargetNodeSpec {

  @Override
  @Value.Parameter
  public abstract BuildFileSpec getBuildFileSpec();

  @Value.Default
  public boolean onlyTests() {
    return false;
  }

  @Override
  public TargetType getTargetType() {
    return TargetType.MULTIPLE_TARGETS;
  }

  @Override
  public ImmutableMap<BuildTarget, TargetNode<?>> filter(Iterable<TargetNode<?>> nodes) {
    ImmutableMap.Builder<BuildTarget, TargetNode<?>> resultBuilder = ImmutableMap.builder();

    for (TargetNode<?> node : nodes) {
      if (!onlyTests() || node.getRuleType().isTestRule()) {
        resultBuilder.put(node.getBuildTarget(), node);
      }
    }

    return resultBuilder.build();
  }

  @Override
  public BuildTargetPattern getBuildTargetPattern(Cell cell) {
    BuildFileSpec buildFileSpec = getBuildFileSpec();
    if (!cell.getRoot().equals(buildFileSpec.getCellPath())) {
      throw new IllegalArgumentException(
          String.format(
              "Root of cell should agree with build file spec: %s vs %s",
              toString(), cell.getRoot(), buildFileSpec.getCellPath()));
    }

    String cellName = cell.getCanonicalName().getName();

    // sometimes spec comes with absolute path as base path, sometimes it is relative to
    // cell path
    // TODO(sergeyb): find out why
    Path basePath = buildFileSpec.getBasePath();
    if (basePath.isAbsolute()) {
      basePath = buildFileSpec.getCellPath().relativize(basePath);
    }
    return ImmutableBuildTargetPattern.of(
        cellName,
        buildFileSpec.isRecursive()
            ? BuildTargetPattern.Kind.RECURSIVE
            : BuildTargetPattern.Kind.PACKAGE,
        basePath,
        "");
  }
}
