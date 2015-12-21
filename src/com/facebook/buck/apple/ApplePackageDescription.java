/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class ApplePackageDescription implements
    Description<ApplePackageDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<ApplePackageDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_package");

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    BuildRule bundle = resolver.getRule(
        propagateFlavorsToTarget(params.getBuildTarget(), args.bundle));
    if (!(bundle instanceof BuildRuleWithAppleBundle)) {
      throw new HumanReadableException(
          "In %s, bundle='%s' must be an apple_bundle() but was %s().",
          params.getBuildTarget(),
          bundle.getFullyQualifiedName(),
          bundle.getType());
    }
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    return new ApplePackage(
        params,
        sourcePathResolver,
        ((BuildRuleWithAppleBundle) bundle).getAppleBundle());
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  private BuildTarget propagateFlavorsToTarget(BuildTarget fromTarget, BuildTarget toTarget) {
    return BuildTarget.builder(toTarget)
        .addAllFlavors(fromTarget.getFlavors())
        .build();
  }

  /**
   * Propagate the packages's flavors to its dependents.
   */
  @Override
  public ImmutableSet<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      ApplePackageDescription.Arg constructorArg) {
    return ImmutableSet.<BuildTarget>of(
        propagateFlavorsToTarget(buildTarget, constructorArg.bundle));
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    @Hint(isDep = false) public BuildTarget bundle;
  }
}
