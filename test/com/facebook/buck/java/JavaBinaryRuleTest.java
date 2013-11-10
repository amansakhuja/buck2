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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JavaBinaryRuleTest {

  private static final String PATH_TO_GUAVA_JAR = "third_party/guava/guava-10.0.1.jar";
  private static final String PATH_TO_GENERATOR_JAR = "third_party/guava/generator.jar";

  @Test
  public void testGetExecutableCommand() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // prebuilt_jar //third_party/generator:generator
    ruleResolver.buildAndAddToIndex(
        PrebuiltJarRule.newPrebuiltJarRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/generator:generator"))
        .setBinaryJar(PATH_TO_GENERATOR_JAR)
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    // prebuilt_jar //third_party/guava:guava
    ruleResolver.buildAndAddToIndex(
        PrebuiltJarRule.newPrebuiltJarRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//third_party/guava:guava"))
        .setBinaryJar(PATH_TO_GUAVA_JAR)
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    // java_library //java/com/facebook/base:base
    JavaLibraryRule javaLibraryRule = ruleResolver.buildAndAddToIndex(
        DefaultJavaLibraryRule.newJavaLibraryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/facebook/base:base"))
        .addSrc("java/com/facebook/base/Base.java")
        .addDep(BuildTargetFactory.newInstance("//third_party/guava:guava")));

    // java_binary //java/com/facebook/base:Main
    JavaBinaryRule javaBinaryRule = ruleResolver.buildAndAddToIndex(
        JavaBinaryRule.newJavaBinaryRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(BuildTargetFactory.newInstance("//java/com/facebook/base:Main"))
        .addDep(BuildTargetFactory.newInstance("//java/com/facebook/base:base"))
        .setMainClass("com.facebook.base.Main"));

    // Strip the trailing "." from the absolute path to the current directory.
    final String basePath = new File(".").getAbsolutePath().replaceFirst("\\.$", "");

    // Each classpath entry is specified via its absolute path so that the executable command can be
    // run from a /tmp directory, if necessary.
    String expectedClasspath =
        basePath + PATH_TO_GUAVA_JAR + ":" +
        basePath + javaLibraryRule.getPathToOutputFile();

    String expectedCommand = String.format("java -classpath %s com.facebook.base.Main",
        expectedClasspath);
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    Function<String, Path> pathRelativizer = new Function<String, Path>() {
      @Override
      public Path apply(String path) {
        return Paths.get(basePath, path);
      }
    };
    expect(projectFilesystem.getPathRelativizer()).andReturn(pathRelativizer);
    replay(projectFilesystem);
    assertEquals(expectedCommand, javaBinaryRule.getExecutableCommand(projectFilesystem));
    verify(projectFilesystem);

    assertFalse(
        "Library rules that are used exclusively by genrules should not be part of the classpath.",
        expectedClasspath.contains(PATH_TO_GENERATOR_JAR));
  }
}
