/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;

public class TestBuildRuleResolver extends SingleThreadedBuildRuleResolver {

  public TestBuildRuleResolver(
      TargetGraph targetGraph,
      TargetNodeToBuildRuleTransformer buildRuleGenerator,
      ToolchainProvider toolchainProvider) {
    super(targetGraph, buildRuleGenerator, toolchainProvider, null);
  }

  public TestBuildRuleResolver(
      TargetGraph targetGraph, TargetNodeToBuildRuleTransformer buildRuleGenerator) {
    super(targetGraph, buildRuleGenerator, new ToolchainProviderBuilder().build(), null);
  }

  public TestBuildRuleResolver(TargetNodeToBuildRuleTransformer buildRuleGenerator) {
    this(TargetGraph.EMPTY, buildRuleGenerator);
  }

  public TestBuildRuleResolver(TargetGraph targetGraph) {
    this(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
  }

  public TestBuildRuleResolver(TargetGraph targetGraph, ToolchainProvider toolchainProvider) {
    this(targetGraph, new DefaultTargetNodeToBuildRuleTransformer(), toolchainProvider);
  }

  public TestBuildRuleResolver() {
    this(TargetGraph.EMPTY);
  }
}
