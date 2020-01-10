/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Defines an apple_toolchain rule that allows a {@link AppleCxxPlatform} to be configured as a
 * build target.
 */
public class AppleToolchainDescription
    implements DescriptionWithTargetGraph<AppleToolchainDescriptionArg> {

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AppleToolchainDescriptionArg args) {
    Verify.verify(!buildTarget.isFlavored());

    ImmutableSortedMap.Builder<String, ApplePlatformBuildRule> appleSdkMappingBuilder =
        new ImmutableSortedMap.Builder<>(Ordering.natural());
    for (Map.Entry<String, BuildTarget> entry : args.getApplePlatforms().entrySet()) {
      if (!ApplePlatform.ALL_PLATFORM_FLAVORS.contains(InternalFlavor.of(entry.getKey()))) {
        throw new HumanReadableException(
            "%s: Invalid Apple platform name: %s", buildTarget, entry.getKey());
      }
      BuildRule applePlatformRule = context.getActionGraphBuilder().getRule(entry.getValue());
      if (!(applePlatformRule instanceof ApplePlatformBuildRule)) {
        throw new HumanReadableException(
            "Expected %s to be an instance of apple_platform.", entry.getValue());
      }
      appleSdkMappingBuilder.put(entry.getKey(), (ApplePlatformBuildRule) applePlatformRule);
    }
    return new AppleToolchainBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        appleSdkMappingBuilder.build(),
        args.getDeveloperPath()
            .map(
                sourcePath ->
                    context
                        .getActionGraphBuilder()
                        .getSourcePathResolver()
                        .getAbsolutePath(sourcePath)),
        args.getXcodeVersion(),
        args.getXcodeBuildVersion());
  }

  @Override
  public Class<AppleToolchainDescriptionArg> getConstructorArgType() {
    return AppleToolchainDescriptionArg.class;
  }

  /**
   * An apple_toolchain is a mapping from platform name to apple_sdk with several common fields for
   * all SDKs.
   */
  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractAppleToolchainDescriptionArg extends BuildRuleArg {
    /** Mapping from apple platform name to apple_platform rule. */
    ImmutableSortedMap<String, BuildTarget> getApplePlatforms();

    /** Developer directory of the toolchain */
    Optional<SourcePath> getDeveloperPath();

    /** XCode version which can be found in DTXcode in XCode plist */
    String getXcodeVersion();

    /** XCode build version from from 'xcodebuild -version' */
    String getXcodeBuildVersion();
  }
}
