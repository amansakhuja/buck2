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

import static com.facebook.buck.util.BuckConstant.GEN_DIR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildRuleFactoryParams;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.NonCheckingBuildRuleFactoryParams;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.easymock.EasyMock;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Unit test for {@link com.facebook.buck.android.ApkGenrule}.
 */
public class ApkGenruleTest {

  private static final Function<String, String> relativeToAbsolutePathFunction =
      new Function<String, String>() {
        @Override
        public String apply(String path) {
          return String.format("/opt/local/fbandroid/%s", path);
        }
      };

  private void createSampleAndroidBinaryRule(Map<String, BuildRule> buildRuleIndex) {
    // Create a java_binary that depends on a java_library so it is possible to create a
    // java_binary rule with a classpath entry and a main class.
    JavaLibraryRule androidLibraryRule = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:lib-android"))
        .addSrc("java/com/facebook/util/Facebook.java")
        .build(buildRuleIndex);
    buildRuleIndex.put(androidLibraryRule.getFullyQualifiedName(), androidLibraryRule);

    AndroidBinaryRule androidBinaryRule = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:fb4a"))
        .setManifest("AndroidManifest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("keystore.properties")
        .addDep("//:lib-android")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .build(buildRuleIndex);
    buildRuleIndex.put(androidBinaryRule.getFullyQualifiedName(), androidBinaryRule);
  }

  @Test
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  public void testCreateAndRunApkGenrule() throws IOException, NoSuchBuildTargetException {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    createSampleAndroidBinaryRule(buildRuleIndex);
    Map<String, ?> instance = new ImmutableMap.Builder<String, Object>()
        .put("name", "fb4a_signed")
        .put("srcs", ImmutableList.<String>of("signer.py", "key.properties"))
        .put("cmd", "python signer.py $APK key.properties > $OUT")
        .put("apk", ":fb4a")
        .put("out", "signed_fb4a.apk")
        .put("deps", ImmutableList.<Object>of())
        .build();

    // From the Python object, create a ApkGenruleBuildRuleFactory to create a ApkGenrule.Builder
    // that builds a ApkGenrule from the Python object.
    BuildTargetParser parser = EasyMock.createNiceMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse(EasyMock.eq(":fb4a"), EasyMock.anyObject(ParseContext.class)))
        .andStubReturn(BuildTargetFactory.newInstance("//:fb4a"));
    EasyMock.replay(parser);

    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//src/com/facebook", "sign_fb4a");
    BuildRuleFactoryParams params = NonCheckingBuildRuleFactoryParams.
        createNonCheckingBuildRuleFactoryParams(
            instance,
            parser,
            buildTarget);

    ApkGenruleBuildRuleFactory factory = new ApkGenruleBuildRuleFactory();
    ApkGenrule.Builder builder = (ApkGenrule.Builder)factory.newInstance(params);
    builder.setRelativeToAbsolutePathFunction(relativeToAbsolutePathFunction);
    ApkGenrule apk_genrule = builder.build(buildRuleIndex);

    // Verify all of the observers of the Genrule.
    String expectedApkOutput = "/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/sign_fb4a.apk";
    assertEquals(BuildRuleType.APK_GENRULE, apk_genrule.getType());
    assertEquals(expectedApkOutput,
        apk_genrule.getOutputFilePath());
    BuildContext buildContext = BuildContext.builder()
        .setProjectRoot(EasyMock.createNiceMock(File.class))
        .setDependencyGraph(EasyMock.createMock(DependencyGraph.class))
        .setCommandRunner(EasyMock.createNiceMock(StepRunner.class))
        .setProjectFilesystem(EasyMock.createNiceMock(ProjectFilesystem.class))
        .setArtifactCache(EasyMock.createMock(ArtifactCache.class))
        .setJavaPackageFinder(EasyMock.createNiceMock(JavaPackageFinder.class))
        .build();
    ImmutableSortedSet<String> inputsToCompareToOutputs = ImmutableSortedSet.of(
        "src/com/facebook/key.properties",
        "src/com/facebook/signer.py");
    assertEquals(inputsToCompareToOutputs,
        apk_genrule.getInputsToCompareToOutput(buildContext));

    // Verify that the shell commands that the genrule produces are correct.
    List<Step> steps = apk_genrule.buildInternal(buildContext);
    assertEquals(7, steps.size());

    Step firstStep = steps.get(0);
    assertTrue(firstStep instanceof ShellStep);
    ShellStep rmCommand = (ShellStep) firstStep;
    ExecutionContext executionContext = null;
    assertEquals(
        "First command should delete the output file to be written by the genrule.",
        ImmutableList.of(
            "rm",
            "-f",
            expectedApkOutput),
        rmCommand.getShellCommand(executionContext));

    Step secondStep = steps.get(1);
    assertTrue(secondStep instanceof MkdirStep);
    MkdirStep mkdirCommand = (MkdirStep) secondStep;
    assertEquals(
        "Second command should make sure the output directory exists.",
        ImmutableList.of("mkdir", "-p", GEN_DIR + "/src/com/facebook/"),
        mkdirCommand.getShellCommand(executionContext));

    Step thirdStep = steps.get(2);
    assertTrue(thirdStep instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep secondMkdirCommand = (MakeCleanDirectoryStep) thirdStep;
    String tempDirPath = "/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/sign_fb4a__tmp";
    assertEquals(
        "Third command should make sure the temp directory exists.",
        tempDirPath,
        secondMkdirCommand.getPath());

    Step fourthStep = steps.get(3);
    assertTrue(fourthStep instanceof MakeCleanDirectoryStep);
    MakeCleanDirectoryStep thirdMkdirCommand = (MakeCleanDirectoryStep) fourthStep;
    String srcDirPath = "/opt/local/fbandroid/" + GEN_DIR + "/src/com/facebook/sign_fb4a__srcs";
    assertEquals(
        "Fourth command should make sure the temp directory exists.",
        srcDirPath,
        thirdMkdirCommand.getPath());

    MkdirAndSymlinkFileStep linkSource1 = (MkdirAndSymlinkFileStep) steps.get(4);
    assertEquals("/opt/local/fbandroid/src/com/facebook/signer.py",
        linkSource1.getSource().getAbsolutePath());
    assertEquals(srcDirPath + "/signer.py", linkSource1.getTarget().getAbsolutePath());

    MkdirAndSymlinkFileStep linkSource2 = (MkdirAndSymlinkFileStep) steps.get(5);
    assertEquals("/opt/local/fbandroid/src/com/facebook/key.properties",
        linkSource2.getSource().getAbsolutePath());
    assertEquals(srcDirPath + "/key.properties", linkSource2.getTarget().getAbsolutePath());

    Step seventhStep = steps.get(6);
    assertTrue(seventhStep instanceof ShellStep);
    ShellStep genruleCommand = (ShellStep) seventhStep;
    assertEquals("genrule: python signer.py $APK key.properties > $OUT",
        genruleCommand.getShortName(executionContext));
    assertEquals(new ImmutableMap.Builder<String, String>()
        .put("SRCS", "/opt/local/fbandroid/src/com/facebook/signer.py " +
            "/opt/local/fbandroid/src/com/facebook/key.properties")
        .put("APK", GEN_DIR + "/fb4a.apk")
        .put("DEPS", "")
        .put("TMP", tempDirPath)
        .put("SRCDIR", srcDirPath)
        .put("OUT", expectedApkOutput).build(),
        genruleCommand.getEnvironmentVariables());
    assertEquals(
        ImmutableList.of("/bin/bash", "-c", "python signer.py $APK key.properties > $OUT"),
        genruleCommand.getShellCommand(executionContext));

    EasyMock.verify(parser);
  }
}
