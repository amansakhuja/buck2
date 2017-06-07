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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.jvm.java.DefaultJavaLibraryBuilder;
import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.util.Optional;
import java.util.logging.Level;
import org.immutables.value.Value;

public class GroovyTestDescription
    implements Description<GroovyTestDescriptionArg>,
        ImplicitDepsInferringDescription<GroovyTestDescription.AbstractGroovyTestDescriptionArg> {

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(ImmutableMap.of("location", new LocationMacroExpander()));

  private final GroovyBuckConfig groovyBuckConfig;
  private final JavaOptions javaOptions;
  private final JavacOptions defaultJavacOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public GroovyTestDescription(
      GroovyBuckConfig groovyBuckConfig,
      JavaOptions javaOptions,
      JavacOptions defaultJavacOptions,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.groovyBuckConfig = groovyBuckConfig;
    this.javaOptions = javaOptions;
    this.defaultJavacOptions = defaultJavacOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
  }

  @Override
  public Class<GroovyTestDescriptionArg> getConstructorArgType() {
    return GroovyTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      GroovyTestDescriptionArg args)
      throws NoSuchBuildTargetException {
    JavacOptions javacOptions =
        JavacOptionsFactory.create(defaultJavacOptions, params, resolver, args);

    BuildRuleParams testsLibraryParams =
        params.withAppendedFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);
    DefaultJavaLibraryBuilder defaultJavaLibraryBuilder =
        new DefaultGroovyLibraryBuilder(
                targetGraph,
                testsLibraryParams,
                resolver,
                cellRoots,
                javacOptions,
                groovyBuckConfig)
            .setArgs(args)
            .setGeneratedSourceFolder(defaultJavacOptions.getGeneratedSourceFolderName());

    if (HasJavaAbi.isAbiTarget(params.getBuildTarget())) {
      return defaultJavaLibraryBuilder.buildAbi();
    }

    JavaLibrary testsLibrary = resolver.addToIndex(defaultJavaLibraryBuilder.build());

    Function<String, Arg> toMacroArgFunction =
        MacroArg.toMacroArgFunction(MACRO_HANDLER, params.getBuildTarget(), cellRoots, resolver);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return new JavaTest(
        params.copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        pathResolver,
        testsLibrary,
        /* additionalClasspathEntries */ ImmutableSet.of(),
        args.getLabels(),
        args.getContacts(),
        args.getTestType().orElse(TestType.JUNIT),
        javaOptions.getJavaRuntimeLauncher(),
        args.getVmArgs(),
        /* nativeLibsEnvironment */ ImmutableMap.of(),
        args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.getTestCaseTimeoutMs(),
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), toMacroArgFunction::apply)),
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.getStdOutLogLevel(),
        args.getStdErrLogLevel());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractGroovyTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    for (String envValue : constructorArg.getEnv().values()) {
      try {
        MACRO_HANDLER.extractParseTimeDeps(
            buildTarget, cellRoots, envValue, extraDepsBuilder, targetGraphOnlyDepsBuilder);
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGroovyTestDescriptionArg
      extends HasContacts, HasTestTimeout, GroovyLibraryDescription.CoreArg {
    ImmutableList<String> getVmArgs();

    Optional<TestType> getTestType();

    Optional<Level> getStdErrLogLevel();

    Optional<Level> getStdOutLogLevel();

    Optional<Long> getTestCaseTimeoutMs();

    ImmutableMap<String, String> getEnv();

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.Default
    default ForkMode getForkMode() {
      return ForkMode.NONE;
    }
  }
}
