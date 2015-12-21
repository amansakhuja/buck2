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

import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

public class JavaBinaryIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Before
  public void checkPlatform() {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
  }

  @Test
  public void fatJarLoadingNativeLibraries() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "fat_jar", tmp);
    workspace.setUp();
    workspace.runBuckCommand("run", "//:bin-fat").assertSuccess();
  }

  @Test
  public void fatJarWithOutput() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "fat_jar", tmp);
    workspace.setUp();
    Path jar = workspace.buildAndReturnOutput("//:bin-output");
    ProcessExecutor.Result result = workspace.runJar(jar);
    assertEquals("output", result.getStdout().get().trim());
    assertEquals("error", result.getStderr().get().trim());
  }

  @Test
  public void fatJarWithExitCode() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "fat_jar", tmp);
    workspace.setUp();
    workspace.runBuckCommand("run", "//:bin-exit-code").assertSpecialExitCode("error", 5);
  }

  @Test
  public void fatJarWithVmArguments() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "fat_jar", tmp);
    workspace.setUp();
    ImmutableList<String> args = ImmutableList.of(
        "-ea",
        "-Dfoo.bar.baz=1234",
        "-Xms64m");
    String expected = Joiner.on("\n").join(args);
    Path jar = workspace.buildAndReturnOutput("//:bin-jvm-args");
    ProcessExecutor.Result result = workspace.runJar(jar, args);
    assertEquals(expected, result.getStdout().get().trim());
  }

  @Test
  public void fatJarWithAlternateJavaBin() throws IOException, InterruptedException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "fat_jar", tmp);
    workspace.setUp();
    Path jar = workspace.buildAndReturnOutput("//:bin-alternate-java");
    String javaHomeArg = "-Dbuck.fatjar.java.home=" + tmp.getRootPath().toString();
    ProcessExecutor.Result result = workspace.runJar(jar, ImmutableList.of(javaHomeArg));
    assertEquals("Running java wrapper\nRunning inner jar", result.getStdout().get().trim());
  }
}
