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

package com.facebook.buck.android.toolchain;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.NdkCxxPlatform;
import com.facebook.buck.android.NdkCxxPlatforms;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public class NdkCxxPlatformsProvider {

  private final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> ndkCxxPlatforms;

  NdkCxxPlatformsProvider(
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> ndkCxxPlatforms) {
    this.ndkCxxPlatforms = ndkCxxPlatforms;
  }

  public ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> getNdkCxxPlatforms() {
    return ndkCxxPlatforms;
  }

  public static NdkCxxPlatformsProvider create(
      BuckConfig config,
      ProjectFilesystem filesystem,
      AndroidDirectoryResolver androidDirectoryResolver) {

    Platform platform = Platform.detect();
    AndroidBuckConfig androidConfig = new AndroidBuckConfig(config, platform);
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);

    Optional<String> ndkVersion = androidConfig.getNdkVersion();
    if (!ndkVersion.isPresent()) {
      ndkVersion = androidDirectoryResolver.getNdkVersion();
    }

    ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> ndkCxxPlatforms =
        NdkCxxPlatforms.getPlatforms(
            cxxBuckConfig,
            androidConfig,
            filesystem,
            androidDirectoryResolver,
            platform,
            ndkVersion);

    return new NdkCxxPlatformsProvider(ndkCxxPlatforms);
  }
}
