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

package com.facebook.buck.jvm.java;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;

public class JavaConfiguredCompilerFactory extends ConfiguredCompilerFactory {
  private final JavaBuckConfig javaBuckConfig;
  private final ExtraClasspathFromContextFunction extraClasspathFromContextFunction;

  public JavaConfiguredCompilerFactory(
      JavaBuckConfig javaBuckConfig,
      ExtraClasspathFromContextFunction extraClasspathFromContextFunction) {
    this.javaBuckConfig = javaBuckConfig;
    this.extraClasspathFromContextFunction = extraClasspathFromContextFunction;
  }

  @Override
  public ConfiguredCompiler configure(
      JvmLibraryArg arg, JavacOptions javacOptions, BuildRuleResolver resolver) {

    return new JavacToJarStepFactory(
        getJavac(resolver, arg), javacOptions, extraClasspathFromContextFunction);
  }

  private Javac getJavac(BuildRuleResolver resolver, JvmLibraryArg arg) {
    return JavacFactory.create(new SourcePathRuleFinder(resolver), javaBuckConfig, arg);
  }
}
