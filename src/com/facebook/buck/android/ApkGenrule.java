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

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.InstallableBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A specialization of a genrule that specifically allows the modification of apks.  This is
 * useful for processes that modify an APK, such as zipaligning it or signing it.
 * <p>
 * The generated APK will be at <code><em>rule_name</em>.apk</code>.
 * <pre>
 * apk_genrule(
 *   name = 'fb4a_signed',
 *   apk = ':fbandroid_release'
 *   deps = [
 *     '//java/com/facebook/sign:fbsign_jar',
 *   ],
 *   cmd = '${//java/com/facebook/sign:fbsign_jar} --input $APK --output $OUT'
 * )
 * </pre>
 */
public class ApkGenrule extends Genrule implements InstallableBuildRule {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID);
  private final InstallableBuildRule apk;

  private ApkGenrule(BuildRuleParams buildRuleParams,
      List<String> srcs,
      Optional<String> cmd,
      Optional<String> bash,
      Optional<String> cmdExe,
      Function<String, Path> relativeToAbsolutePathFunction,
      InstallableBuildRule apk) {
    super(buildRuleParams,
        srcs,
        cmd,
        bash,
        cmdExe,
        /* out */ buildRuleParams.getBuildTarget().getShortName() + ".apk",
        relativeToAbsolutePathFunction);

    this.apk = Preconditions.checkNotNull(apk);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.APK_GENRULE;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    return super.appendToRuleKey(builder)
        .set("apk", apk);
  }

  public InstallableBuildRule getInstallableBuildRule() {
    return apk;
  }

  @Override
  public String getManifest() {
    return apk.getManifest();
  }

  @Override
  public String getApkPath() {
    return getAbsoluteOutputFilePath();
  }

  @Override
  public ImmutableSortedSet<String> getInputsToCompareToOutput() {
    return super.getInputsToCompareToOutput();
  }

  @Override
  public String getPathToOutputFile() {
    return pathToOutFile.toString();
  }

  public static Builder newApkGenruleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  @Override
  protected void addEnvironmentVariables(
      ExecutionContext context,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    super.addEnvironmentVariables(context, environmentVariablesBuilder);
    environmentVariablesBuilder.put("APK", apk.getApkPath());
  }

  public static class Builder extends Genrule.Builder {

    private BuildTarget apk;

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public ApkGenrule build(BuildRuleResolver ruleResolver) {
      // Verify that the 'apk' field is set and corresponds to an installable rule.

      BuildRule apkRule = ruleResolver.get(apk);

      Preconditions.checkState(apk != null && apkRule != null,
          "Buck should guarantee that apk was set and included in the deps of this rule, " +
          "so apk should not be null at this point and should have an entry in buildRuleIndex " +
          "as all deps should.");

      if (!(apkRule instanceof InstallableBuildRule)) {
        throw new HumanReadableException("The 'apk' argument of %s, %s, must correspond to an " +
        		"installable rule, such as android_binary() or apk_genrule().",
        		getBuildTarget(),
            apkRule.getFullyQualifiedName());
      }

      BuildRuleParams buildRuleParams = createBuildRuleParams(ruleResolver);
      return new ApkGenrule(createBuildRuleParams(ruleResolver),
          srcs,
          cmd,
          bash,
          cmdExe,
          getRelativeToAbsolutePathFunction(buildRuleParams),
          (InstallableBuildRule)apkRule);
    }

    public Builder setApk(BuildTarget apk) {
      this.apk = apk;
      return this;
    }
  }
}
