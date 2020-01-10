/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.model.graph;

import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Container class for {@link ActionGraphAndBuilder} and {@link TargetGraphCreationResult}. Also
 * contains helper methods to choose which {@link TargetGraph} to use ({@link
 * com.facebook.buck.versions.VersionedTargetGraph} vs un-versioned).
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractActionAndTargetGraphs {

  abstract TargetGraphCreationResult getUnversionedTargetGraph();

  abstract Optional<TargetGraphCreationResult> getVersionedTargetGraph();

  public abstract ActionGraphAndBuilder getActionGraphAndBuilder();

  /** Helper method to choose versioned vs un-versioned {@link TargetGraph}. */
  public static TargetGraphCreationResult getTargetGraph(
      TargetGraphCreationResult unversionedTargetGraph,
      Optional<TargetGraphCreationResult> versionedTargetGraph) {
    // If a versioned target graph was produced then we always use it,
    // otherwise the unversioned graph is used.
    return versionedTargetGraph.orElse(unversionedTargetGraph);
  }

  /** Helper method to get the appropriate {@link TargetGraph}. */
  public TargetGraphCreationResult getTargetGraph() {
    return getTargetGraph(getUnversionedTargetGraph(), getVersionedTargetGraph());
  }
}
