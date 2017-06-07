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

package com.facebook.buck.halide;

import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.base.Preconditions;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.regex.Pattern;

public class HalideLibrary extends NoopBuildRule implements CxxPreprocessorDep, NativeLinkable {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final Optional<Pattern> supportedPlatformsRegex;

  private final LoadingCache<CxxPlatform, ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      transitiveCxxPreprocessorInputCache =
          CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  protected HalideLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      Optional<Pattern> supportedPlatformsRegex) {
    super(params);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent()
        || supportedPlatformsRegex.get().matcher(cxxPlatform.getFlavor().toString()).find();
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return FluentIterable.from(getBuildDeps()).filter(CxxPreprocessorDep.class);
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    if (!isPlatformSupported(cxxPlatform)) {
      return CxxPreprocessorInput.EMPTY;
    }
    return CxxPreprocessables.getCxxPreprocessorInput(
        params,
        ruleResolver,
        /* hasHeaderSymlinkTree */ true,
        cxxPlatform,
        HeaderVisibility.PUBLIC,
        CxxPreprocessables.IncludeType.SYSTEM,
        ImmutableMultimap.of(),
        ImmutableList.of());
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform) {
    return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps() {
    return FluentIterable.from(getDeclaredDeps()).filter(NativeLinkable.class);
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDepsForPlatform(CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    return getNativeLinkableDeps();
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableExportedDeps() {
    return ImmutableList.of();
  }

  private Arg requireLibraryArg(CxxPlatform cxxPlatform, Linker.LinkableDepType type)
      throws NoSuchBuildTargetException {
    BuildRule rule =
        ruleResolver.requireRule(
            getBuildTarget()
                .withFlavors(
                    CxxDescriptionEnhancer.flavorForLinkableDepType(type),
                    cxxPlatform.getFlavor()));
    if (rule instanceof Archive) {
      return ((Archive) rule).toArg();
    } else {
      return SourcePathArg.of(Preconditions.checkNotNull(rule.getSourcePathToOutput()));
    }
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform, Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    if (!isPlatformSupported(cxxPlatform)) {
      return NativeLinkableInput.of();
    }
    return NativeLinkableInput.of(
        ImmutableList.of(requireLibraryArg(cxxPlatform, type)),
        ImmutableSet.of(),
        ImmutableSet.of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return NativeLinkable.Linkage.STATIC;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.of();
  }
}
