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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.DefaultJavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;


public class DefaultKotlinLibraryBuilder extends DefaultJavaLibraryBuilder {
  private final KotlinBuckConfig kotlinBuckConfig;
  private ImmutableList<String> extraKotlincArguments = ImmutableList.of();

  public DefaultKotlinLibraryBuilder(
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      KotlinBuckConfig kotlinBuckConfig) {
    super(params, buildRuleResolver);
    this.kotlinBuckConfig = kotlinBuckConfig;
    setSuggestDependencies(kotlinBuckConfig.shouldSuggestDependencies());
  }

  @Override
  public DefaultKotlinLibraryBuilder setArgs(JavaLibraryDescription.Arg args) {
    super.setArgs(args);

    KotlinLibraryDescription.Arg kotlinArgs = (KotlinLibraryDescription.Arg) args;
    extraKotlincArguments = kotlinArgs.extraKotlincArguments;
    return this;
  }

  @Override
  protected BuilderHelper newHelper() {
    return new BuilderHelper();
  }

  protected class BuilderHelper extends DefaultJavaLibraryBuilder.BuilderHelper {
    @Override
    protected CompileToJarStepFactory buildCompileStepFactory() {
      return new KotlincToJarStepFactory(
          Preconditions.checkNotNull(kotlinBuckConfig).getKotlinCompiler().get(),
          extraKotlincArguments);
    }
  }
}
