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

package com.facebook.buck.d;

import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class DBinaryDescription implements Description<DBinaryDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("d_binary");

  private static final Flavor BINARY_FLAVOR = ImmutableFlavor.of("binary");

  private final DBuckConfig dBuckConfig;
  private final CxxPlatform cxxPlatform;

  public DBinaryDescription(
      DBuckConfig dBuckConfig,
      CxxPlatform cxxPlatform) {
    this.dBuckConfig = dBuckConfig;
    this.cxxPlatform = cxxPlatform;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      A args) throws NoSuchBuildTargetException {

    // Create a rule that actually builds the binary, and add that
    // rule to the index.
    CxxLink nativeLinkable = DDescriptionUtils.createNativeLinkable(
        params.copyWithBuildTarget(
            BuildTarget.builder().from(params.getBuildTarget()).addFlavors(BINARY_FLAVOR).build()),
        args.srcs,
        /* compilerFlags */ ImmutableList.<String>of(),
        buildRuleResolver,
        cxxPlatform,
        dBuckConfig);
    buildRuleResolver.addToIndex(nativeLinkable);

    // Create a Tool for the executable.
    CommandTool.Builder executableBuilder = new CommandTool.Builder();
    executableBuilder.addArg(
        new BuildTargetSourcePath(
            nativeLinkable.getBuildTarget()));

    // Return a BinaryBuildRule implementation, so that this works
    // with buck run etc.
    return new DBinary(
        params.copyWithExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(nativeLinkable))),
        new SourcePathResolver(buildRuleResolver),
        executableBuilder.build());
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public ImmutableSortedSet<SourcePath> srcs;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
