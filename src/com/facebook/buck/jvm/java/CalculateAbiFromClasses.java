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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;

public class CalculateAbiFromClasses extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements CalculateAbi, InitializableFromDisk<Object>, SupportsInputBasedRuleKey {

  @AddToRuleKey private final SourcePath binaryJar;
  /**
   * Strip out things that are intentionally not included in ABI jars generated from source, so that
   * we can still detect bugs by binary comparison.
   */
  @AddToRuleKey private final boolean sourceAbiCompatible;

  private final Path outputPath;
  private final JarContentsSupplier abiJarContentsSupplier;

  public CalculateAbiFromClasses(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      SourcePath binaryJar,
      boolean sourceAbiCompatible) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.binaryJar = binaryJar;
    this.sourceAbiCompatible = sourceAbiCompatible;
    this.outputPath = getAbiJarPath(getProjectFilesystem(), getBuildTarget());
    this.abiJarContentsSupplier = new JarContentsSupplier(resolver, getSourcePathToOutput());
  }

  public static CalculateAbiFromClasses of(
      BuildTarget target,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams libraryParams,
      SourcePath library) {
    return of(target, ruleFinder, projectFilesystem, libraryParams, library, false);
  }

  public static CalculateAbiFromClasses of(
      BuildTarget target,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams libraryParams,
      SourcePath library,
      boolean sourceAbiCompatible) {
    return new CalculateAbiFromClasses(
        target,
        projectFilesystem,
        libraryParams
            .withDeclaredDeps(ImmutableSortedSet.copyOf(ruleFinder.filterBuildRuleInputs(library)))
            .withoutExtraDeps(),
        DefaultSourcePathResolver.from(ruleFinder),
        library,
        sourceAbiCompatible);
  }

  public static Path getAbiJarPath(ProjectFilesystem filesystem, BuildTarget buildTarget) {
    return BuildTargets.getGenPath(filesystem, buildTarget, "%s")
        .resolve(String.format("%s-abi.jar", buildTarget.getShortName()));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList<Step> result =
        ImmutableList.of(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    outputPath.getParent())),
            RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), outputPath)),
            new CalculateAbiFromClassesStep(
                getProjectFilesystem(),
                context.getSourcePathResolver().getAbsolutePath(binaryJar),
                outputPath,
                sourceAbiCompatible));

    buildableContext.recordArtifact(outputPath);

    return result;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputPath);
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return abiJarContentsSupplier.get();
  }

  @Override
  public Object initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    abiJarContentsSupplier.load();
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return new BuildOutputInitializer<>(getBuildTarget(), this);
  }
}
