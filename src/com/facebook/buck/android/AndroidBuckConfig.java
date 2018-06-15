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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.ndk.NdkCompilerType;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class AndroidBuckConfig {
  private static final String APP_PLATFORM_KEY_PREFIX = "app_platform-";

  private final BuckConfig delegate;
  private final Platform platform;

  public AndroidBuckConfig(BuckConfig delegate, Platform platform) {
    this.delegate = delegate;
    this.platform = platform;
  }

  public Optional<String> getAndroidTarget() {
    return delegate.getValue("android", "target");
  }

  public Optional<String> getBuildToolsVersion() {
    return delegate.getValue("android", "build_tools_version");
  }

  public Integer getAdbTimeout() {
    return delegate.getInteger("android", "adb_timeout").orElse(60000);
  }

  public Optional<String> getSdkPath() {
    return delegate.getValue("android", "sdk_path");
  }

  public Optional<String> getNdkVersion() {
    return delegate.getValue("ndk", "ndk_version");
  }

  public Optional<String> getNdkPath() {
    return delegate.getValue("ndk", "ndk_path");
  }

  public Optional<String> getNdkRepositoryPath() {
    return delegate.getValue("ndk", "ndk_repository_path");
  }

  public Optional<String> getNdkCpuAbiAgnosticAppPlatform() {
    return delegate.getValue("ndk", "app_platform");
  }

  public Optional<Set<String>> getNdkCpuAbis() {
    return delegate.getOptionalListWithoutComments("ndk", "cpu_abis").map(ImmutableSet::copyOf);
  }

  public Optional<NdkCompilerType> getNdkCompiler() {
    return delegate.getEnum("ndk", "compiler", NdkCompilerType.class);
  }

  public Optional<String> getNdkGccVersion() {
    return delegate.getValue("ndk", "gcc_version");
  }

  public Optional<String> getNdkClangVersion() {
    return delegate.getValue("ndk", "clang_version");
  }

  public Optional<NdkCxxRuntime> getNdkCxxRuntime() {
    return delegate.getEnum("ndk", "cxx_runtime", NdkCxxRuntime.class);
  }

  public ImmutableList<String> getExtraNdkCFlags() {
    return delegate.getListWithoutComments("ndk", "extra_cflags", ' ');
  }

  public ImmutableList<String> getExtraNdkCxxFlags() {
    return delegate.getListWithoutComments("ndk", "extra_cxxflags", ' ');
  }

  public ImmutableList<String> getExtraNdkLdFlags() {
    return delegate.getListWithoutComments("ndk", "extra_ldflags", ' ');
  }

  public boolean isGrayscaleImageProcessingEnabled() {
    return delegate.getBooleanValue("resources", "resource_grayscale_enabled", false);
  }

  /**
   * Returns the CPU specific app platform, or the agnostic one if set. If neither are set, returns
   * `Optional.empty` instead of a default value so callers can determine the difference between
   * user-set and buck defaults.
   */
  public Optional<String> getNdkAppPlatformForCpuAbi(String cpuAbi) {
    ImmutableMap<String, String> platformMap = getNdkAppPlatformMap();
    Optional<String> specificAppPlatform = Optional.ofNullable(platformMap.get(cpuAbi));
    return specificAppPlatform.isPresent()
        ? specificAppPlatform
        : getNdkCpuAbiAgnosticAppPlatform();
  }

  private ImmutableMap<String, String> getNdkAppPlatformMap() {
    ImmutableMap<String, String> allEntries = delegate.getEntriesForSection("ndk");
    ImmutableMap.Builder<String, String> platforms = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : allEntries.entrySet()) {
      if (entry.getKey().startsWith(APP_PLATFORM_KEY_PREFIX)) {
        platforms.put(entry.getKey().substring(APP_PLATFORM_KEY_PREFIX.length()), entry.getValue());
      }
    }
    return platforms.build();
  }

  /**
   * Returns the path to the platform specific aapt executable that is overridden by the current
   * project. If not specified, the Android platform aapt will be used.
   */
  public Optional<Path> getAaptOverride() {
    return getToolOverride("aapt");
  }

  /**
   * Returns the path to the platform specific aapt2 executable that is overridden by the current
   * project. If not specified, the Android platform aapt will be used.
   */
  public Optional<Path> getAapt2Override() {
    return getToolOverride("aapt2");
  }

  private Optional<Path> getToolOverride(String tool) {
    Optional<String> pathString = delegate.getValue("tools", tool);
    if (!pathString.isPresent()) {
      return Optional.empty();
    }

    String platformDir;
    if (platform == Platform.LINUX) {
      platformDir = "linux";
    } else if (platform == Platform.MACOS) {
      platformDir = "mac";
    } else if (platform == Platform.WINDOWS) {
      platformDir = "windows";
    } else {
      return Optional.empty();
    }

    Path pathToTool = Paths.get(pathString.get(), platformDir, tool);
    return Optional.of(
        delegate.checkPathExistsAndResolve(
            pathToTool.toString(),
            String.format("Overridden %s:%s path not found: ", "tools", tool)));
  }
}
