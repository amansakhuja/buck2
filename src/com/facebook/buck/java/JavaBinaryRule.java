/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractCachingBuildRule;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

public class JavaBinaryRule extends AbstractCachingBuildRule implements BinaryBuildRule,
    HasClasspathEntries {

  @Nullable
  private final String mainClass;

  @Nullable
  private final String manifestFile;

  @Nullable
  private final String metaInfDirectory;

  private final DirectoryTraverser directoryTraverser;

  JavaBinaryRule(
      BuildRuleParams buildRuleParams,
      @Nullable String mainClass,
      @Nullable String manifestFile,
      @Nullable String metaInfDirectory,
      DirectoryTraverser directoryTraverser) {
    super(buildRuleParams);
    this.mainClass = mainClass;
    this.manifestFile = manifestFile;
    this.metaInfDirectory = metaInfDirectory;

    this.directoryTraverser = Preconditions.checkNotNull(directoryTraverser);
  }

  private void addMetaInfContents(ImmutableSortedSet.Builder<String> files) {
    addInputsToSortedSet(metaInfDirectory, files, directoryTraverser);
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    ImmutableSortedSet.Builder<String> metaInfFiles = ImmutableSortedSet.naturalOrder();
    addMetaInfContents(metaInfFiles);

    return super.ruleKeyBuilder()
        .set("mainClass", mainClass)
        .set("manifestFile", manifestFile)
        .set("metaInfDirectory", metaInfFiles.build());
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.JAVA_BINARY;
  }

  @Override
  protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
    // Build a sorted set so that metaInfDirectory contents are listed in a canonical order.
    ImmutableSortedSet.Builder<String> builder = ImmutableSortedSet.naturalOrder();

    if (manifestFile != null) {
      builder.add(manifestFile);
    }

    addMetaInfContents(builder);

    return builder.build();
  }

  @Override
  protected List<Step> buildInternal(BuildContext context) throws IOException {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    String outputDirectory = getOutputDirectory();
    Step mkdir = new MkdirStep(outputDirectory);
    commands.add(mkdir);

    ImmutableSet<String> includePaths;
    if (metaInfDirectory != null) {
      String stagingRoot = outputDirectory + "/meta_inf_staging";
      String stagingTarget = stagingRoot + "/META-INF";

      MakeCleanDirectoryStep createStagingRoot = new MakeCleanDirectoryStep(stagingRoot);
      commands.add(createStagingRoot);

      MkdirAndSymlinkFileStep link = new MkdirAndSymlinkFileStep(
          metaInfDirectory, stagingTarget);
      commands.add(link);

      includePaths = ImmutableSet.<String>builder()
          .add(stagingRoot)
          .addAll(getTransitiveClasspathEntries().values())
          .build();
    } else {
      includePaths = ImmutableSet.copyOf(getTransitiveClasspathEntries().values());
    }

    String outputFile = getOutputFile();
    Step jar = new JarDirectoryStep(outputFile, includePaths, mainClass, manifestFile);
    commands.add(jar);

    return commands.build();
  }

  @Override
  public ImmutableSetMultimap<BuildRule, String> getTransitiveClasspathEntries() {
    return Classpaths.getClasspathEntries(getDeps());
  }

  private String getOutputDirectory() {
    return String.format("%s/%s", BuckConstant.GEN_DIR, getBuildTarget().getBasePath());
  }

  @Override
  public File getOutput() {
    return new File(getOutputFile());
  }

  String getOutputFile() {
    return String.format("%s/%s.jar", getOutputDirectory(), getBuildTarget().getShortName());
  }

  public static Builder newJavaBinaryRuleBuilder() {
    return new Builder();
  }

  @Override
  public boolean isPackagingRule() {
    return true;
  }

  @Override
  public String getExecutableCommand() {
    Preconditions.checkState(mainClass != null,
        "Must specify a main class for %s in order to to run it.",
        getBuildTarget().getFullyQualifiedName());

    return String.format("java -classpath %s %s",
        Joiner.on(':').join(Iterables.transform(
            getTransitiveClasspathEntries().values(),
            Functions.RELATIVE_TO_ABSOLUTE_PATH)),
        mainClass);
  }

  public static class Builder extends AbstractBuildRuleBuilder {

    private String mainClass;
    private String manifestFile;
    private String metaInfDirectory;

    private Builder() {}

    @Override
    public JavaBinaryRule build(Map<String, BuildRule> buildRuleIndex) {
      return new JavaBinaryRule(createBuildRuleParams(buildRuleIndex),
          mainClass,
          manifestFile,
          metaInfDirectory,
          new DefaultDirectoryTraverser());
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(String dep) {
      super.addDep(dep);
      return this;
    }

    public Builder setMainClass(String mainClass) {
      this.mainClass = mainClass;
      return this;
    }

    public Builder setManifest(String manifestFile) {
      this.manifestFile = manifestFile;
      return this;
    }

    public Builder setMetaInfDirectory(String metaInfDirectory) {
      this.metaInfDirectory = metaInfDirectory;
      return this;
    }
  }
}
