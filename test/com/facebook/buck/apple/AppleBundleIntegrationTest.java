/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.FakeAppleDeveloperEnvironment;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleBundleIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private boolean checkCodeSigning(String relativeBundlePath)
      throws IOException, InterruptedException {
    Path absoluteBundlePath = tmp.getRootPath()
        .resolve(BuckConstant.GEN_DIR)
        .resolve(Paths.get(relativeBundlePath));

    return CodeSigning.hasValidSignature(
        new ProcessExecutor(new TestConsole()),
        absoluteBundlePath);
  }

  @Test
  public void simpleApplicationBundle() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_no_debug",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();

    workspace.verify();

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve(
                    "DemoApp#iphonesimulator-x86_64,no-debug,no-include-frameworks/DemoApp.app/" +
                        "DemoApp")));

    assertFalse(checkCodeSigning("DemoApp#iphonesimulator-x86_64,no-debug/DemoApp.app"));
  }

  @Test
  public void simpleApplicationBundleWithCodeSigning() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_with_codesigning",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphoneos-arm64,no-debug").assertSuccess();

    workspace.verify();

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve(
                    "DemoApp#iphoneos-arm64,no-debug,no-include-frameworks/DemoApp.app/" +
                        "DemoApp")));

    assertTrue(
        checkCodeSigning("DemoApp#iphoneos-arm64,no-debug,no-include-frameworks/DemoApp.app"));
  }

  @Test
  public void simpleApplicationBundleWithCodeSigningAndEntitlements()
      throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(FakeAppleDeveloperEnvironment.supportsCodeSigning());

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_with_codesigning_and_entitlements",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphoneos-arm64,no-debug").assertSuccess();

    workspace.verify();

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve(
                    "DemoApp#iphoneos-arm64,no-debug,no-include-frameworks/DemoApp.app/DemoApp")));

    assertTrue(
        checkCodeSigning("DemoApp#iphoneos-arm64,no-debug,no-include-frameworks/DemoApp.app"));
  }

  @Test
  public void simpleApplicationBundleWithFatBinary() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_fat_application_bundle_no_debug",
        tmp);
    workspace.setUp();
    workspace.runBuckCommand(
        "build",
        "//:DemoApp#iphonesimulator-i386,iphonesimulator-x86_64,no-debug")
        .assertSuccess();
    workspace.verify();

    Path outputFile = tmp.getRootPath()
        .resolve(BuckConstant.GEN_DIR)
        .resolve(
            "DemoApp#iphonesimulator-i386,iphonesimulator-x86_64,no-debug,no-include-frameworks/" +
                "DemoApp.app/DemoApp");

    assertTrue(Files.exists(outputFile));
    ProcessExecutor.Result result = workspace.runCommand(
        "lipo",
        outputFile.toString(),
        "-verify_arch", "i386", "x86_64");
    assertEquals(0, result.getExitCode());
  }

  @Test
  public void bundleHasOutputPath() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_no_debug",
        tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand("targets", "--show-output", "//:DemoApp#no-debug");
    result.assertSuccess();
    assertEquals(
        "//:DemoApp#no-debug buck-out/gen/DemoApp#no-debug,no-include-frameworks/DemoApp.app",
        result.getStdout().trim());
  }

  @Test
  public void extensionBundle() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_extension",
        tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand("targets", "--show-output", "//:DemoExtension#no-debug");
    result.assertSuccess();
    assertEquals(
        "//:DemoExtension#no-debug buck-out/gen/DemoExtension#no-debug,no-include-frameworks/" +
            "DemoExtension.appex",
        result.getStdout().trim());

    result = workspace
        .runBuckCommand("build", "//:DemoExtension#no-debug");
    result.assertSuccess();
    Path outputBinary = tmp.getRootPath()
        .resolve(BuckConstant.GEN_DIR)
        .resolve("DemoExtension#no-debug,no-include-frameworks/DemoExtension.appex/DemoExtension");
    assertTrue(
        String.format(
            "Extension binary could not be found inside the appex dir [%s].",
            outputBinary),
        Files.exists(outputBinary));
  }

  @Test
  public void appBundleWithExtensionBundleDependency() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_app_with_extension",
        tmp);
    workspace.setUp();

    ProjectWorkspace.ProcessResult result = workspace
        .runBuckCommand("targets", "--show-output", "//:DemoAppWithExtension#no-debug");
    result.assertSuccess();
    assertEquals(
        "//:DemoAppWithExtension#no-debug " +
            "buck-out/gen/DemoAppWithExtension#no-debug,no-include-frameworks/" +
            "DemoAppWithExtension.app",
        result.getStdout().trim());

    result = workspace
        .runBuckCommand("build", "//:DemoAppWithExtension#no-debug");
    result.assertSuccess();
    Path bundleDir = tmp.getRootPath()
        .resolve(BuckConstant.GEN_DIR)
        .resolve("DemoAppWithExtension#no-debug,no-include-frameworks/DemoAppWithExtension.app");
    assertTrue(Files.exists(bundleDir.resolve("DemoAppWithExtension")));
    assertTrue(Files.exists(bundleDir.resolve("PlugIns/DemoExtension.appex/DemoExtension")));
  }

  @Test
  public void bundleBinaryHasDsymBundle() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_application_bundle_dwarf_and_dsym",
        tmp);
    workspace.setUp();

    workspace
        .runBuckCommand("build", "//:DemoApp#dwarf-and-dsym,iphonesimulator-x86_64")
        .assertSuccess();

    workspace.verify();

    Path bundlePath = tmp.getRootPath()
        .resolve(BuckConstant.GEN_DIR)
        .resolve(
            "DemoApp#dwarf-and-dsym,iphonesimulator-x86_64,no-include-frameworks/DemoApp.app");
    Path dwarfPath = bundlePath
        .getParent()
        .resolve("DemoApp.app.dSYM/Contents/Resources/DWARF/DemoApp");
    Path binaryPath = bundlePath.resolve("DemoApp");
    assertTrue(Files.exists(dwarfPath));
    String dwarfdumpMainStdout =
        workspace.runCommand("dwarfdump", "-n", "main", dwarfPath.toString()).getStdout().or("");
    assertTrue(dwarfdumpMainStdout.contains("AT_name"));
    assertTrue(dwarfdumpMainStdout.contains("AT_decl_file"));
    assertTrue(dwarfdumpMainStdout.contains("AT_decl_line"));

    ProcessExecutor.Result result = workspace.runCommand(
        "dsymutil",
        "-o",
        binaryPath.toString() + ".test.dSYM",
        binaryPath.toString());

    String dsymutilOutput = "";
    if (result.getStderr().isPresent()) {
      dsymutilOutput = result.getStderr().get();
    }
    if (dsymutilOutput.isEmpty()) {
      assertThat(result.getStdout().isPresent(), is(true));
      dsymutilOutput = result.getStdout().get();
    }
    assertThat(dsymutilOutput, containsString("warning: no debug symbols in executable"));
  }

  @Test
  public void appBundleWithResources() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "app_bundle_with_resources",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();

    workspace.verify();
  }

  @Test
  public void appBundleVariantDirectoryMustEndInLproj() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Variant files have to be in a directory with name ending in '.lproj', " +
            "but 'cc/Localizable.strings' is not.");

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "app_bundle_with_invalid_variant",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertFailure();
  }

  @Test
  public void defaultPlatformInBuckConfig() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "default_platform_in_buckconfig_app_bundle",
        tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//:DemoApp").assertSuccess();

    workspace.verify();

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve("DemoApp#no-debug,no-include-frameworks/DemoApp.app/DemoApp")));
  }

  @Test
  public void defaultPlatformInBuckConfigWithFlavorSpecified() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "default_platform_in_buckconfig_flavored_app_bundle",
        tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();

    workspace.verify();

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve(
                    "DemoApp#iphonesimulator-x86_64,no-debug,no-include-frameworks/" +
                        "DemoApp.app/DemoApp")));
  }

  @Test
  public void appleAssetCatalogsAreIncludedInBundle() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "apple_asset_catalogs_are_included_in_bundle",
        tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//:DemoApp#no-debug").assertSuccess();

    System.err.println(tmp.getRootPath());
    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve("DemoApp#no-debug,no-include-frameworks/DemoApp.app/Assets.car")));

    workspace.verify();
  }

  @Test
  public void infoPlistSubstitutionsAreApplied() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "application_bundle_with_substitutions",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();

    workspace.verify();

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve(
                    "DemoApp#iphonesimulator-x86_64,no-debug,no-include-frameworks/" +
                        "DemoApp.app/DemoApp")));
  }

  @Test
  public void productNameChangesBundleAndBinaryNames() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "application_bundle_with_product_name",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve(
                    "DemoApp#iphonesimulator-x86_64,no-debug,no-include-frameworks/" +
                    "BrandNewProduct.app/BrandNewProduct")));
  }

  @Test
  public void infoPlistWithUnrecognizedVariableFails() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "application_bundle_with_invalid_substitutions",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertFailure();
  }

  @Test
  public void resourcesAreCompiled() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "app_bundle_with_xib_and_storyboard",
        tmp);
    workspace.setUp();
    workspace.runBuckCommand("build", "//:DemoApp#iphonesimulator-x86_64,no-debug").assertSuccess();

    workspace.verify();

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve(
                    "DemoApp#iphonesimulator-x86_64,no-debug,no-include-frameworks/DemoApp.app/" +
                        "AppViewController.nib")));
  }

  @Test
  public void watchApplicationBundle() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() == Platform.MACOS);
    assumeTrue(AppleNativeIntegrationTestUtils.isApplePlatformAvailable(ApplePlatform.WATCHOS));

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "watch_application_bundle",
        tmp);
    workspace.setUp();

    workspace.runBuckCommand("build", "//:DemoApp#no-debug").assertSuccess();

    workspace.verify();

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve(
                    "DemoApp#no-debug,no-include-frameworks/DemoApp.app/Watch/" +
                        "DemoWatchApp.app/DemoWatchApp")));

    assertTrue(
        Files.exists(
            tmp.getRootPath()
                .resolve(BuckConstant.GEN_DIR)
                .resolve(
                    "DemoApp#no-debug,no-include-frameworks/DemoApp.app/Watch/" +
                        "DemoWatchApp.app/PlugIns/DemoWatchAppExtension.appex/" +
                        "DemoWatchAppExtension")));
  }
}
