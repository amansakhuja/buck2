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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

/**
 * Integration test for the {@code buck project} command.
 */
public class ProjectIntegrationTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testBuckProject() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "project1", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("project");
    assertEquals("buck project should exit cleanly", 0, result.getExitCode());

    workspace.verify();

    String stderr = result.getStderr();
    assertTrue(
        "Nothing should be written to stderr except possibly an Android platform warning.",
        stderr.isEmpty() || stderr.startsWith("No Android platform target specified."));
    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
          "MODIFIED FILES:",
          ".idea/compiler.xml",
          ".idea/libraries/guava.xml",
          ".idea/libraries/jsr305.xml",
          ".idea/libraries/junit.xml",
          ".idea/modules.xml",
          ".idea/runConfigurations/Debug_Buck_test.xml",
          "modules/dep1/module_modules_dep1.iml",
          "modules/tip/module_modules_tip.iml"
        ) + '\n',
        result.getStdout());
  }

  /**
   * Tests the case where a build file has a test rule that depends on a library rule in the same
   * build file, and the test rule is specified as the {@code test_target} in its
   * {@code project_config()}. When this happens, all libraries in the generated {@code .iml} file
   * should be listed before any of the modules.
   * <p>
   * This prevents a regression where JUnit was not being loaded early enough in the classpath,
   * which led to a "JUnit version 3.8 or later expected" error when running tests in IntelliJ.
   * (Presumably, IntelliJ ended up loading JUnit 3 from android.jar instead of loading JUnit 4
   * from the version of JUnit checked into version control.)
   */
  @Test
  public void testBuckProjectWithMultipleLibrariesInOneBuildFile() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "buck_project_multiple_libraries_in_one_build_file", temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand("project");
    assertEquals("buck project should exit cleanly", 0, result.getExitCode());

    workspace.verify();
  }
}
