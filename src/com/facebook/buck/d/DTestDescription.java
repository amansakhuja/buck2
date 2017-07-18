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

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

public class DTestDescription
    implements Description<DTestDescriptionArg>,
        ImplicitDepsInferringDescription<DTestDescription.AbstractDTestDescriptionArg>,
        VersionRoot<DTestDescriptionArg> {

  private final DBuckConfig dBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform cxxPlatform;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public DTestDescription(
      DBuckConfig dBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.dBuckConfig = dBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatform = cxxPlatform;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
  }

  @Override
  public Class<DTestDescriptionArg> getConstructorArgType() {
    return DTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      CellPathResolver cellRoots,
      DTestDescriptionArg args)
      throws NoSuchBuildTargetException {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    SymlinkTree sourceTree =
        buildRuleResolver.addToIndex(
            DDescriptionUtils.createSourceSymlinkTree(
                DDescriptionUtils.getSymlinkTreeTarget(buildTarget),
                buildTarget,
                projectFilesystem,
                pathResolver,
                args.getSrcs()));

    // Create a helper rule to build the test binary.
    // The rule needs its own target so that we can depend on it without creating cycles.
    BuildTarget binaryTarget =
        DDescriptionUtils.createBuildTargetForFile(
            buildTarget, "build-", buildTarget.getFullyQualifiedName(), cxxPlatform);

    BuildRule binaryRule =
        DDescriptionUtils.createNativeLinkable(
            binaryTarget,
            projectFilesystem,
            params,
            buildRuleResolver,
            cxxPlatform,
            dBuckConfig,
            cxxBuckConfig,
            ImmutableList.of("-unittest"),
            args.getSrcs(),
            args.getLinkerFlags(),
            DIncludes.builder()
                .setLinkTree(sourceTree.getSourcePathToOutput())
                .addAllSources(args.getSrcs().getPaths())
                .build());
    buildRuleResolver.addToIndex(binaryRule);

    return new DTest(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(ImmutableList.of(binaryRule)),
        binaryRule,
        args.getContacts(),
        args.getLabels(),
        args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractDTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.addAll(cxxPlatform.getLd().getParseTimeDeps());
  }

  @Override
  public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractDTestDescriptionArg
      extends CommonDescriptionArg, HasContacts, HasDeclaredDeps, HasTestTimeout {
    SourceList getSrcs();

    ImmutableList<String> getLinkerFlags();
  }
}
