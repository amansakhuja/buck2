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

package com.facebook.buck.jvm.scala;

import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibraryRules;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.util.Optional;
import org.immutables.value.Value;

public class ScalaTestDescription
    implements Description<ScalaTestDescriptionArg>,
        ImplicitDepsInferringDescription<ScalaTestDescription.AbstractScalaTestDescriptionArg> {

  private final ToolchainProvider toolchainProvider;
  private final ScalaBuckConfig config;
  private final JavaBuckConfig javaBuckConfig;

  public ScalaTestDescription(
      ToolchainProvider toolchainProvider, ScalaBuckConfig config, JavaBuckConfig javaBuckConfig) {
    this.toolchainProvider = toolchainProvider;
    this.config = config;
    this.javaBuckConfig = javaBuckConfig;
  }

  @Override
  public Class<ScalaTestDescriptionArg> getConstructorArgType() {
    return ScalaTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams rawParams,
      ScalaTestDescriptionArg args) {
    BuildRuleResolver resolver = context.getBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            buildTarget,
            projectFilesystem,
            rawParams,
            args.getUseCxxLibraries(),
            args.getCxxLibraryWhitelist(),
            resolver,
            ruleFinder,
            toolchainProvider
                .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
                .getDefaultCxxPlatform());
    BuildRuleParams params = cxxLibraryEnhancement.updatedParams;
    BuildTarget javaLibraryBuildTarget =
        buildTarget.withAppendedFlavors(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            toolchainProvider
                .getByName(JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.class)
                .getJavacOptions(),
            buildTarget,
            projectFilesystem,
            resolver,
            args);

    CellPathResolver cellRoots = context.getCellPathResolver();
    DefaultJavaLibraryRules scalaLibraryBuilder =
        ScalaLibraryBuilder.newInstance(
                javaLibraryBuildTarget,
                projectFilesystem,
                context.getToolchainProvider(),
                params,
                resolver,
                cellRoots,
                config,
                javaBuckConfig,
                args)
            .setJavacOptions(javacOptions)
            .build();

    if (HasJavaAbi.isAbiTarget(buildTarget)) {
      return scalaLibraryBuilder.buildAbi();
    }

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(cellRoots)
            .setResolver(resolver)
            .setExpanders(JavaTestDescription.MACRO_EXPANDERS)
            .build();
    JavaLibrary testsLibrary = resolver.addToIndex(scalaLibraryBuilder.buildLibrary());

    return new JavaTest(
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(testsLibrary)).withoutExtraDeps(),
        testsLibrary,
        /* additionalClasspathEntries */ ImmutableSet.of(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().isPresent() ? args.getTestType().get() : TestType.JUNIT,
        toolchainProvider
            .getByName(JavaOptionsProvider.DEFAULT_NAME, JavaOptionsProvider.class)
            .getJavaOptionsForTests()
            .getJavaRuntimeLauncher(),
        args.getVmArgs(),
        cxxLibraryEnhancement.nativeLibsEnvironment,
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(javaBuckConfig.getDelegate().getDefaultTestRuleTimeoutMs()),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), macrosConverter::convert)),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel(),
        args.getUnbundledResourcesRoot());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractScalaTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extraDepsBuilder.add(config.getScalaLibraryTarget());
    Optionals.addIfPresent(config.getScalacTarget(), extraDepsBuilder);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractScalaTestDescriptionArg
      extends ScalaLibraryDescription.CoreArg, JavaTestDescription.CoreArg {}
}
