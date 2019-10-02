/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableCollection;
import java.util.Optional;

/** Represents different android tools */
@BuckStyleValue
public interface AndroidTools {

  AndroidPlatformTarget getAndroidPlatformTarget();

  AndroidSdkLocation getAndroidSdkLocation();

  Optional<AndroidNdk> getAndroidNdk();

  /** Returns {@code AndroidTools} derived from a given {@code toolProvider} */
  static AndroidTools getAndroidTools(ToolchainProvider toolchainProvider) {
    AndroidPlatformTarget androidPlatformTarget =
        toolchainProvider.getByName(
            AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class);
    Optional<AndroidNdk> androidNdk =
        toolchainProvider.getByNameIfPresent(AndroidNdk.DEFAULT_NAME, AndroidNdk.class);
    AndroidSdkLocation androidSdkLocation =
        toolchainProvider.getByName(AndroidSdkLocation.DEFAULT_NAME, AndroidSdkLocation.class);

    return new ImmutableAndroidTools(androidPlatformTarget, androidSdkLocation, androidNdk);
  }

  /** Adds parse time deps to android tools */
  static void addParseTimeDepsToAndroidTools(
      ToolchainProvider toolchainProvider,
      BuildTarget buildTarget,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    toolchainProvider
        .getByNameIfPresent(AndroidPlatformTarget.DEFAULT_NAME, AndroidPlatformTarget.class)
        .ifPresent(
            androidPlatformTarget ->
                androidPlatformTarget.addParseTimeDeps(
                    targetGraphOnlyDepsBuilder, buildTarget.getTargetConfiguration()));
  }
}
