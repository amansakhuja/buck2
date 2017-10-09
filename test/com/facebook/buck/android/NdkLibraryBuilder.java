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

import com.facebook.buck.android.toolchain.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.NdkCxxRuntime;
import com.facebook.buck.android.toolchain.TargetCpuType;
import com.facebook.buck.android.toolchain.TestAndroidToolchain;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.TestToolchainProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;

public class NdkLibraryBuilder
    extends AbstractNodeBuilder<
        NdkLibraryDescriptionArg.Builder, NdkLibraryDescriptionArg, NdkLibraryDescription,
        NdkLibrary> {

  private static final NdkCxxPlatform DEFAULT_NDK_PLATFORM =
      NdkCxxPlatform.builder()
          .setCxxPlatform(CxxPlatformUtils.DEFAULT_PLATFORM)
          .setCxxRuntime(NdkCxxRuntime.GNUSTL)
          .setCxxSharedRuntimePath(Paths.get("runtime"))
          .setObjdump(new CommandTool.Builder().addArg("objdump").build())
          .build();

  private static final ImmutableMap<TargetCpuType, NdkCxxPlatform> NDK_PLATFORMS =
      ImmutableMap.<TargetCpuType, NdkCxxPlatform>builder()
          .put(TargetCpuType.ARM, DEFAULT_NDK_PLATFORM)
          .put(TargetCpuType.ARMV7, DEFAULT_NDK_PLATFORM)
          .put(TargetCpuType.X86, DEFAULT_NDK_PLATFORM)
          .build();

  public NdkLibraryBuilder(BuildTarget target) {
    this(target, new FakeProjectFilesystem());
  }

  public NdkLibraryBuilder(BuildTarget target, ProjectFilesystem filesystem) {
    super(
        new NdkLibraryDescription(createToolchainProvider(), NDK_PLATFORMS) {
          @Override
          protected ImmutableSortedSet<SourcePath> findSources(
              ProjectFilesystem filesystem, Path buildRulePath) {
            return ImmutableSortedSet.of(
                PathSourcePath.of(filesystem, buildRulePath.resolve("Android.mk")));
          }
        },
        target,
        filesystem);
  }

  private static ToolchainProvider createToolchainProvider() {
    TestToolchainProvider toolchainProvider = new TestToolchainProvider();
    toolchainProvider.addAndroidToolchain(new TestAndroidToolchain());
    return toolchainProvider;
  }

  public NdkLibraryBuilder addDep(BuildTarget target) {
    getArgForPopulating().addDeps(target);
    return this;
  }

  public NdkLibraryBuilder setFlags(Iterable<String> flags) {
    getArgForPopulating().setFlags(ImmutableList.copyOf(flags));
    return this;
  }

  public NdkLibraryBuilder setIsAsset(boolean isAsset) {
    getArgForPopulating().setIsAsset(isAsset);
    return this;
  }
}
