/*
 * Copyright 2004-present Facebook, Inc.
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

package com.facebook.buck.simulate;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractSingleRunReport {
  public abstract long getTimestampMillis();
  public abstract long getBuildDurationMillis();
  public abstract int getTotalActionGraphNodes();
  public abstract int getUsedActionGraphNodes();
  public abstract int getTotalDependencyDagEdges();
  public abstract ImmutableList<String> getBuildTargets();
  public abstract int getNumberOfThreads();
  public abstract int getActionGraphNodesWithoutSimulateTime();
  public abstract String getSimulateTimesFile();
  public abstract String getTimeAggregate();
  public abstract long getRuleFallbackTimeMillis();
}
