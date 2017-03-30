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

package com.facebook.buck.jvm.groovy;

import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;



public class GroovyLibraryDescription implements Description<GroovyLibraryDescription.Arg> {

  private final GroovyBuckConfig groovyBuckConfig;
  // For cross compilation
  private final JavacOptions defaultJavacOptions;

  public GroovyLibraryDescription(
      GroovyBuckConfig groovyBuckConfig,
      JavacOptions defaultJavacOptions) {
    this.groovyBuckConfig = groovyBuckConfig;
    this.defaultJavacOptions = defaultJavacOptions;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    if (CalculateAbi.isAbiTarget(params.getBuildTarget())) {
      BuildTarget libraryTarget = CalculateAbi.getLibraryTarget(params.getBuildTarget());
      BuildRule libraryRule = resolver.requireRule(libraryTarget);
      return CalculateAbi.of(
          params.getBuildTarget(),
          ruleFinder,
          params,
          Preconditions.checkNotNull(libraryRule.getSourcePathToOutput()));
    }

    JavacOptions javacOptions = JavacOptionsFactory
        .create(
          defaultJavacOptions,
          params,
          resolver,
          args)
        // groovyc may or may not play nice with generating ABIs from source, so disabling for now
        .withAbiGenerationMode(JavacOptions.AbiGenerationMode.CLASS);
    return
        new DefaultGroovyLibraryBuilder(params, resolver, javacOptions, groovyBuckConfig)
        .setArgs(args)
        .build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaLibraryDescription.Arg {
    public ImmutableList<String> extraGroovycArguments = ImmutableList.of();
  }
}
