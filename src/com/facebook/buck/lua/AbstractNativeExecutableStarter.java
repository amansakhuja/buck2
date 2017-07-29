/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.lua;

import com.facebook.buck.cxx.AbstractCxxLibrary;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.cxx.platform.Linker;
import com.facebook.buck.cxx.platform.Linkers;
import com.facebook.buck.cxx.platform.NativeLinkTarget;
import com.facebook.buck.cxx.platform.NativeLinkTargetMode;
import com.facebook.buck.cxx.platform.NativeLinkable;
import com.facebook.buck.cxx.platform.NativeLinkableInput;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.WriteStringTemplateRule;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** {@link Starter} implementation which builds a starter as a native executable. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractNativeExecutableStarter implements Starter, NativeLinkTarget {

  private static final String NATIVE_STARTER_CXX_SOURCE =
      "com/facebook/buck/lua/native-starter.cpp.in";

  abstract ProjectFilesystem getProjectFilesystem();

  abstract BuildTarget getBaseTarget();

  abstract BuildRuleParams getBaseParams();

  abstract BuildRuleResolver getRuleResolver();

  abstract SourcePathResolver getPathResolver();

  abstract SourcePathRuleFinder getRuleFinder();

  abstract LuaConfig getLuaConfig();

  abstract CxxBuckConfig getCxxBuckConfig();

  abstract CxxPlatform getCxxPlatform();

  abstract BuildTarget getTarget();

  abstract Path getOutput();

  abstract String getMainModule();

  abstract Optional<BuildTarget> getNativeStarterLibrary();

  abstract Optional<Path> getRelativeModulesDir();

  abstract Optional<Path> getRelativePythonModulesDir();

  abstract Optional<Path> getRelativeNativeLibsDir();

  private String getNativeStarterCxxSourceTemplate() {
    try {
      return Resources.toString(Resources.getResource(NATIVE_STARTER_CXX_SOURCE), Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private CxxSource getNativeStarterCxxSource() {
    BuildRule rule =
        getRuleResolver()
            .computeIfAbsent(
                getBaseTarget().withAppendedFlavors(InternalFlavor.of("native-starter-cxx-source")),
                target -> {
                  BuildTarget templateTarget =
                      getBaseTarget()
                          .withAppendedFlavors(
                              InternalFlavor.of("native-starter-cxx-source-template"));
                  WriteFile templateRule =
                      getRuleResolver()
                          .addToIndex(
                              new WriteFile(
                                  templateTarget,
                                  getProjectFilesystem(),
                                  getBaseParams().withoutDeclaredDeps().withoutExtraDeps(),
                                  getNativeStarterCxxSourceTemplate(),
                                  BuildTargets.getGenPath(
                                      getProjectFilesystem(),
                                      templateTarget,
                                      "%s/native-starter.cpp.in"),
                                  /* executable */ false));

                  Path output =
                      BuildTargets.getGenPath(
                          getProjectFilesystem(), target, "%s/native-starter.cpp");
                  return WriteStringTemplateRule.from(
                      getProjectFilesystem(),
                      getBaseParams(),
                      getRuleFinder(),
                      target,
                      output,
                      templateRule.getSourcePathToOutput(),
                      ImmutableMap.of(
                          "MAIN_MODULE",
                          Escaper.escapeAsPythonString(getMainModule()),
                          "MODULES_DIR",
                          getRelativeModulesDir().isPresent()
                              ? Escaper.escapeAsPythonString(
                                  getRelativeModulesDir().get().toString())
                              : "NULL",
                          "PY_MODULES_DIR",
                          getRelativePythonModulesDir().isPresent()
                              ? Escaper.escapeAsPythonString(
                                  getRelativePythonModulesDir().get().toString())
                              : "NULL",
                          "EXT_SUFFIX",
                          Escaper.escapeAsPythonString(
                              getCxxPlatform().getSharedLibraryExtension())),
                      /* executable */ false);
                });

    return CxxSource.of(
        CxxSource.Type.CXX,
        Preconditions.checkNotNull(rule.getSourcePathToOutput()),
        ImmutableList.of());
  }

  private ImmutableList<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, Iterable<? extends CxxPreprocessorDep> deps)
      throws NoSuchBuildTargetException {
    ImmutableList.Builder<CxxPreprocessorInput> inputs = ImmutableList.builder();
    inputs.addAll(
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            cxxPlatform, FluentIterable.from(deps).filter(BuildRule.class)));
    for (CxxPreprocessorDep dep :
        Iterables.filter(deps, Predicates.not(BuildRule.class::isInstance))) {
      inputs.add(dep.getCxxPreprocessorInput(cxxPlatform));
    }
    return inputs.build();
  }

  public Iterable<? extends AbstractCxxLibrary> getNativeStarterDeps() {
    return ImmutableList.of(
        getNativeStarterLibrary().isPresent()
            ? getRuleResolver()
                .getRuleWithType(getNativeStarterLibrary().get(), AbstractCxxLibrary.class)
            : getLuaConfig().getLuaCxxLibrary(getRuleResolver()));
  }

  private NativeLinkableInput getNativeLinkableInput() throws NoSuchBuildTargetException {
    Iterable<? extends AbstractCxxLibrary> nativeStarterDeps = getNativeStarterDeps();
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        CxxSourceRuleFactory.of(
                getProjectFilesystem(),
                getBaseTarget(),
                getRuleResolver(),
                getPathResolver(),
                getRuleFinder(),
                getCxxBuckConfig(),
                getCxxPlatform(),
                ImmutableList.<CxxPreprocessorInput>builder()
                    .add(
                        CxxPreprocessorInput.builder()
                            .putAllPreprocessorFlags(
                                CxxSource.Type.CXX,
                                getNativeStarterLibrary().isPresent()
                                    ? ImmutableList.of()
                                    : StringArg.from("-DBUILTIN_NATIVE_STARTER"))
                            .build())
                    .addAll(getTransitiveCxxPreprocessorInput(getCxxPlatform(), nativeStarterDeps))
                    .build(),
                ImmutableMultimap.of(),
                Optional.empty(),
                Optional.empty(),
                CxxSourceRuleFactory.PicType.PDC,
                Optional.empty())
            .requirePreprocessAndCompileRules(
                ImmutableMap.of("native-starter.cpp", getNativeStarterCxxSource()));
    return NativeLinkableInput.builder()
        .addAllArgs(
            getRelativeNativeLibsDir().isPresent()
                ? StringArg.from(
                    Linkers.iXlinker(
                        "-rpath",
                        String.format(
                            "%s/%s",
                            getCxxPlatform().getLd().resolve(getRuleResolver()).origin(),
                            getRelativeNativeLibsDir().get().toString())))
                : ImmutableList.of())
        .addAllArgs(SourcePathArg.from(objects.values()))
        .build();
  }

  @Override
  public SourcePath build() throws NoSuchBuildTargetException {
    BuildTarget linkTarget = getTarget();
    CxxLink linkRule =
        getRuleResolver()
            .addToIndex(
                CxxLinkableEnhancer.createCxxLinkableBuildRule(
                    getCxxBuckConfig(),
                    getCxxPlatform(),
                    getProjectFilesystem(),
                    getRuleResolver(),
                    getPathResolver(),
                    getRuleFinder(),
                    linkTarget,
                    Linker.LinkType.EXECUTABLE,
                    Optional.empty(),
                    getOutput(),
                    Linker.LinkableDepType.SHARED,
                    /* thinLto */ false,
                    getNativeStarterDeps(),
                    Optional.empty(),
                    Optional.empty(),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    getNativeLinkableInput(),
                    Optional.empty()));
    return linkRule.getSourcePathToOutput();
  }

  @Override
  public BuildTarget getBuildTarget() {
    return getBaseTarget();
  }

  @Override
  public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
    return NativeLinkTargetMode.executable();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(CxxPlatform cxxPlatform) {
    return getNativeStarterDeps();
  }

  @Override
  public NativeLinkableInput getNativeLinkTargetInput(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    return getNativeLinkableInput();
  }

  @Override
  public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
    return Optional.of(getOutput());
  }
}
