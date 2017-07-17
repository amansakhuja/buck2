/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.easymock.EasyMock;
import org.junit.Test;

public class JavaSourceJarTest {

  @Test
  public void outputNameShouldIndicateThatTheOutputIsASrcJar() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//example:target");

    JavaSourceJar rule =
        new JavaSourceJar(
            buildTarget,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSortedSet.of(),
            Optional.empty());
    resolver.addToIndex(rule);

    SourcePath output = rule.getSourcePathToOutput();

    assertNotNull(output);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    assertThat(pathResolver.getRelativePath(output).toString(), endsWith(Javac.SRC_JAR));
  }

  @Test
  public void shouldOnlyIncludePathBasedSources() {
    SourcePath fileBased = new FakeSourcePath("some/path/File.java");
    SourcePath ruleBased =
        new DefaultBuildTargetSourcePath(BuildTargetFactory.newInstance("//cheese:cake"));

    JavaPackageFinder finderStub = createNiceMock(JavaPackageFinder.class);
    expect(finderStub.findJavaPackageFolder((Path) anyObject())).andStubReturn(Paths.get("cheese"));
    expect(finderStub.findJavaPackage((Path) anyObject())).andStubReturn("cheese");

    // No need to verify. It's a stub. I don't care about the interactions.
    EasyMock.replay(finderStub);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//example:target");
    JavaSourceJar rule =
        new JavaSourceJar(
            buildTarget,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create(),
            ImmutableSortedSet.of(fileBased, ruleBased),
            Optional.empty());

    BuildContext buildContext =
        FakeBuildContext.withSourcePathResolver(
                DefaultSourcePathResolver.from(
                    new SourcePathRuleFinder(
                        new BuildRuleResolver(
                            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()))))
            .withJavaPackageFinder(finderStub);
    ImmutableList<Step> steps = rule.getBuildSteps(buildContext, new FakeBuildableContext());

    // There should be a CopyStep per file being copied. Count 'em.
    int copyStepsCount = FluentIterable.from(steps).filter(CopyStep.class::isInstance).size();

    assertEquals(1, copyStepsCount);
  }
}
