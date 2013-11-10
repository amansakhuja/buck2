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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRuleFactory;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;

public class AndroidInstrumentationApkRuleFactory
    extends AbstractBuildRuleFactory<AndroidInstrumentationApk.Builder> {

  @Override
  public AndroidInstrumentationApk.Builder newBuilder(AbstractBuildRuleBuilderParams params) {
    return AndroidInstrumentationApk.newAndroidInstrumentationApkRuleBuilder(params);
  }

  @Override
  protected void amendBuilder(AndroidInstrumentationApk.Builder builder,
      BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    // manifest
    String manifestAttribute = params.getRequiredStringAttribute("manifest");
    String manifestPath = params.resolveFilePathRelativeToBuildFileDirectory(manifestAttribute);
    builder.setManifest(manifestPath);

    // apk
    String apk = params.getRequiredStringAttribute("apk");
    BuildTarget buildTarget = params.resolveBuildTarget(apk);
    builder.setApk(buildTarget);

    // classpath_deps
    for (String classpathDep : params.getOptionalListAttribute("classpath_deps")) {
      BuildTarget classpathDepTarget = params.resolveBuildTarget(classpathDep);
      builder.addClasspathDep(classpathDepTarget);
    }
  }
}
