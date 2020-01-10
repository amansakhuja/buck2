/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.CxxToolchainHelper;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AppleToolchainIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws Exception {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void testBuildWithCustomAppleToolchain() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);
    workspace.addBuckConfigLocalOption("apple", "toolchain_target", "//apple_toolchain:toolchain");
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:TestApp#iphoneos-arm64");
    assertEquals("signed by codesign\n", workspace.getFileContents(output.resolve("app_signed")));
    Path rootPath = workspace.getProjectFileSystem().getRootPath();
    assertEquals(
        String.format(
            "strip:%n"
                + "linker: input:%n"
                + "buck-out/gen/TestLib#iphoneos-arm64,static/libTestLib.static.secret%n"
                + "archive:%n"
                + "object: compile output: source code 1%n"
                + "object: compile output: source code 2%n"
                + "ranlib applied.%n"
                + "linker: fpath: %s/apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/Frameworks%n"
                + "linker: frameworks: Foundation,UIKit%n"
                + "linker: lpath:  %s/apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/lib%n"
                + "linker: libs:  objc%n",
            rootPath, rootPath),
        workspace.getFileContents(output.resolve("TestApp")));
  }

  @Test
  public void testBuildWithCustomAppleToolchainMultiArch() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);
    workspace.addBuckConfigLocalOption("apple", "toolchain_target", "//apple_toolchain:toolchain");
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:TestApp#iphoneos-arm64,iphoneos-armv7");
    assertEquals("signed by codesign\n", workspace.getFileContents(output.resolve("app_signed")));
    Path rootPath = workspace.getProjectFileSystem().getRootPath();
    assertEquals(
        String.format(
            "universal file:%n"
                + "strip:%n"
                + "linker: input:%n"
                + "buck-out/gen/TestLib#iphoneos-arm64,static/libTestLib.static.secret%n"
                + "archive:%n"
                + "object: compile output: source code 1%n"
                + "object: compile output: source code 2%n"
                + "ranlib applied.%n"
                + "linker: fpath: %s/apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/Frameworks%n"
                + "linker: frameworks: Foundation,UIKit%n"
                + "linker: lpath:  %s/apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/lib%n"
                + "linker: libs:  objc%n"
                + "universal file:%n"
                + "strip:%n"
                + "linker: input:%n"
                + "buck-out/gen/TestLib#iphoneos-armv7,static/libTestLib.static.secret%n"
                + "archive:%n"
                + "object: compile output: source code 1%n"
                + "object: compile output: source code 2%n"
                + "ranlib applied.%n"
                + "linker: fpath: %s/apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/Frameworks%n"
                + "linker: frameworks: Foundation,UIKit%n"
                + "linker: lpath:  %s/apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/lib%n"
                + "linker: libs:  objc%n",
            rootPath, rootPath, rootPath, rootPath),
        workspace.getFileContents(output.resolve("TestApp")));
  }

  @Test
  public void testBuildWithDsymutilWorkaround() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "apple_toolchain", tmp);
    CxxToolchainHelper.addCxxToolchainToWorkspace(workspace);
    workspace.addBuckConfigLocalOption(
        "apple", "toolchain_target", "//apple_toolchain:toolchain_dsymutil");
    workspace.setUp();
    Path output = workspace.buildAndReturnOutput("//:TestApp#iphoneos-arm64");
    assertEquals("signed by codesign\n", workspace.getFileContents(output.resolve("app_signed")));
    Path rootPath = workspace.getProjectFileSystem().getRootPath();
    assertEquals(
        String.format(
            "strip:%n"
                + "linker: input:%n"
                + "buck-out/gen/TestLib#iphoneos-arm64,static/libTestLib.static.secret%n"
                + "archive:%n"
                + "object: compile output: source code 1%n"
                + "object: compile output: source code 2%n"
                + "ranlib applied.%n"
                + "linker: fpath: %s/apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/Frameworks%n"
                + "linker: frameworks: Foundation,UIKit%n"
                + "linker: lpath:  %s/apple_toolchain/Developer/iPhoneOS.platform/iPhoneOS.sdk/lib%n"
                + "linker: libs:  objc%n",
            rootPath, rootPath),
        workspace.getFileContents(output.resolve("TestApp")));
  }
}
