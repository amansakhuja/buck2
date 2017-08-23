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

package com.facebook.buck.android;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDepsQuery;
import com.facebook.buck.rules.HasProvidedDepsQuery;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

public class AndroidLibraryDescription
    implements Description<AndroidLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<
            AndroidLibraryDescription.AbstractAndroidLibraryDescriptionArg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("android_library");

  private static final Flavor DUMMY_R_DOT_JAVA_FLAVOR =
      AndroidLibraryGraphEnhancer.DUMMY_R_DOT_JAVA_FLAVOR;

  public enum JvmLanguage {
    JAVA,
    KOTLIN,
    SCALA,
  }

  private final JavaBuckConfig javaBuckConfig;
  private final JavacOptions defaultOptions;
  private final AndroidLibraryCompilerFactory compilerFactory;

  public AndroidLibraryDescription(
      JavaBuckConfig javaBuckConfig,
      JavacOptions defaultOptions,
      AndroidLibraryCompilerFactory compilerFactory) {
    this.javaBuckConfig = javaBuckConfig;
    this.defaultOptions = defaultOptions;
    this.compilerFactory = compilerFactory;
  }

  @Override
  public Class<AndroidLibraryDescriptionArg> getConstructorArgType() {
    return AndroidLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AndroidLibraryDescriptionArg args) {
    if (buildTarget.getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(
          buildTarget, projectFilesystem, params, args.getSrcs(), args.getMavenCoords());
    }

    boolean hasDummyRDotJavaFlavor = buildTarget.getFlavors().contains(DUMMY_R_DOT_JAVA_FLAVOR);
    JavacOptions javacOptions =
        JavacOptionsFactory.create(defaultOptions, buildTarget, projectFilesystem, resolver, args);
    AndroidLibrary.Builder defaultJavaLibraryBuilder =
        (AndroidLibrary.Builder)
            AndroidLibrary.builder(
                    targetGraph,
                    buildTarget,
                    projectFilesystem,
                    params,
                    resolver,
                    cellRoots,
                    javaBuckConfig,
                    javacOptions,
                    args,
                    compilerFactory)
                .setArgs(args)
                .setJavacOptions(javacOptions)
                .setTests(args.getTests());

    if (hasDummyRDotJavaFlavor) {
      return defaultJavaLibraryBuilder.buildDummyRDotJava();
    } else if (HasJavaAbi.isAbiTarget(buildTarget)) {
      return defaultJavaLibraryBuilder.buildAbi();
    }
    return defaultJavaLibraryBuilder.build();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.isEmpty()
        || flavors.equals(ImmutableSet.of(JavaLibrary.SRC_JAR))
        || flavors.equals(ImmutableSet.of(DUMMY_R_DOT_JAVA_FLAVOR))
        || flavors.equals(ImmutableSet.of(HasJavaAbi.CLASS_ABI_FLAVOR))
        || flavors.equals(ImmutableSet.of(HasJavaAbi.SOURCE_ABI_FLAVOR))
        || flavors.equals(ImmutableSet.of(HasJavaAbi.VERIFIED_SOURCE_ABI_FLAVOR));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractAndroidLibraryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    compilerFactory
        .getCompiler(constructorArg.getLanguage().orElse(JvmLanguage.JAVA))
        .addTargetDeps(extraDepsBuilder, targetGraphOnlyDepsBuilder);
  }

  public interface CoreArg
      extends JavaLibraryDescription.CoreArg,
          AndroidKotlinCoreArg,
          HasDepsQuery,
          HasProvidedDepsQuery {
    Optional<SourcePath> getManifest();

    Optional<String> getResourceUnionPackage();

    Optional<String> getFinalRName();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidLibraryDescriptionArg extends CoreArg {}
}
