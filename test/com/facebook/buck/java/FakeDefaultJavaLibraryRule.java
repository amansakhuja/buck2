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

package com.facebook.buck.java;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildRuleParams;
import com.google.common.base.Optional;

import java.util.Map;
import java.util.Set;

public class FakeDefaultJavaLibraryRule extends DefaultJavaLibraryRule {

  // TODO(mbolin): Find a way to make use of this field or delete it.
  @SuppressWarnings("unused")
  private final boolean ruleInputsAreCached;

  protected FakeDefaultJavaLibraryRule(CachingBuildRuleParams cachingBuildRuleParams,
                                       Set<String> srcs,
                                       Set<String> resources,
                                       Optional<String> proguardConfig,
                                       AnnotationProcessingParams annotationProcessingParams,
                                       boolean exportDeps,
                                       boolean ruleInputsAreCached) {
    super(cachingBuildRuleParams,
        srcs,
        resources,
        proguardConfig,
        exportDeps,
        JavacOptions.builder().setAnnotationProcessingData(annotationProcessingParams).build()
    );

    this.ruleInputsAreCached = ruleInputsAreCached;
  }

  public static FakeDefaultJavaLibraryRule.Builder newFakeJavaLibraryRuleBuilder() {
    return new FakeDefaultJavaLibraryRule.Builder();
  }

  public static class Builder extends DefaultJavaLibraryRule.Builder {
    private boolean ruleInputsAreCached;

    @Override
    public FakeDefaultJavaLibraryRule build(Map<String, BuildRule> buildRuleIndex) {
      CachingBuildRuleParams cachingBuildRuleParams = createCachingBuildRuleParams(buildRuleIndex);
      AnnotationProcessingParams processingParams =
          annotationProcessingBuilder.build(buildRuleIndex);

      return new FakeDefaultJavaLibraryRule(
          cachingBuildRuleParams,
          srcs,
          resources,
          proguardConfig,
          processingParams,
          exportDeps,
          ruleInputsAreCached);
    }

    public Builder setRuleInputsAreCached(boolean ruleInputsAreCached) {
      this.ruleInputsAreCached = ruleInputsAreCached;
      return this;
    }
  }
}
