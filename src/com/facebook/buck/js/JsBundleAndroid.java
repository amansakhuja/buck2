/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.android.AndroidResource;
import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** Represents a combination of a JavaScript bundle *and* Android resources. */
public class JsBundleAndroid extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements AndroidPackageable, JsBundleOutputs {

  @AddToRuleKey private final JsBundleOutputs delegate;

  @AddToRuleKey private final AndroidResource androidResource;

  public JsBundleAndroid(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      JsBundleOutputs delegate,
      AndroidResource androidResource) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.delegate = delegate;
    this.androidResource = androidResource;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    final SourcePathResolver sourcePathResolver = context.getSourcePathResolver();

    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(getSourcePathToOutput()));
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(getSourcePathToSourceMap()));
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(getSourcePathToResources()));

    final Path jsDir = sourcePathResolver.getRelativePath(getSourcePathToOutput());
    final Path resourcesDir = sourcePathResolver.getRelativePath(getSourcePathToResources());
    final Path sourceMapFile = sourcePathResolver.getRelativePath(getSourcePathToSourceMap());

    return ImmutableList.<Step>builder()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(
                        JsUtil.relativeToOutputRoot(
                            getBuildTarget(), getProjectFilesystem(), "")))))
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), jsDir.getParent())),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    resourcesDir.getParent())),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourceMapFile.getParent())),
            CopyStep.forDirectory(
                getProjectFilesystem(),
                sourcePathResolver.getAbsolutePath(delegate.getSourcePathToOutput()),
                jsDir.getParent(),
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS),
            CopyStep.forDirectory(
                getProjectFilesystem(),
                sourcePathResolver.getAbsolutePath(delegate.getSourcePathToResources()),
                resourcesDir.getParent(),
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS),
            CopyStep.forDirectory(
                getProjectFilesystem(),
                sourcePathResolver.getAbsolutePath(delegate.getSourcePathToSourceMap()),
                sourceMapFile.getParent(),
                CopyStep.DirectoryMode.DIRECTORY_AND_CONTENTS))
        .build();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return ImmutableList.of(androidResource);
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addAssetsDirectory(getBuildTarget(), getSourcePathToOutput());
  }

  @Override
  public String getBundleName() {
    return delegate.getBundleName();
  }
}
