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

package com.facebook.buck.swift;

import static com.facebook.buck.model.UnflavoredBuildTarget.BUILD_TARGET_PREFIX;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.args.StringArg;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Pseudo linkable for representing Swift runtime library's linker arguments. */
final class SwiftRuntimeNativeLinkable implements NativeLinkable {

  private static final String SWIFT_RUNTIME = "_swift_runtime";

  private static final BuildTarget PSEUDO_BUILD_TARGET =
      BuildTarget.of(
          UnflavoredBuildTarget.of(
              Paths.get(SWIFT_RUNTIME),
              Optional.empty(),
              BUILD_TARGET_PREFIX + SWIFT_RUNTIME,
              SWIFT_RUNTIME));
  private final SwiftPlatform swiftPlatform;

  SwiftRuntimeNativeLinkable(SwiftPlatform swiftPlatform) {
    this.swiftPlatform = swiftPlatform;
  }

  @Override
  public BuildTarget getBuildTarget() {
    return PSEUDO_BUILD_TARGET;
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableDeps() {
    return ImmutableSet.of();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
    return ImmutableSet.of();
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<LanguageExtensions> languageExtensions) {
    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();

    ImmutableSet<Path> swiftRuntimePaths =
        type == Linker.LinkableDepType.SHARED
            ? ImmutableSet.of()
            : swiftPlatform.getSwiftStaticRuntimePaths();

    // Fall back to shared if static isn't supported on this platform.
    if (type == Linker.LinkableDepType.SHARED || swiftRuntimePaths.isEmpty()) {
      inputBuilder.addAllArgs(
          StringArg.from(
              "-Xlinker",
              "-rpath",
              "-Xlinker",
              "@executable_path/Frameworks",
              "-Xlinker",
              "-rpath",
              "-Xlinker",
              "@loader_path/Frameworks"));
      swiftRuntimePaths = swiftPlatform.getSwiftRuntimePaths();
    } else {
      // Static linking requires force-loading Swift libs, since the dependency
      // discovery mechanism is disabled otherwise.
      inputBuilder.addAllArgs(StringArg.from("-Xlinker", "-force_load_swift_libs"));
    }
    for (Path swiftRuntimePath : swiftRuntimePaths) {
      inputBuilder.addAllArgs(StringArg.from("-L", swiftRuntimePath.toString()));
    }
    return inputBuilder.build();
  }

  @Override
  public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    return Linkage.ANY;
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform) {
    return ImmutableMap.of();
  }
}
