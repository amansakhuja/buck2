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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.SmartDexingStep.DxPseudoRule;
import com.facebook.buck.android.SmartDexingStep.InputResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SmartDexingStepTest {
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  /**
   * This test makes sure the input processing builds the correct pair of output filenames and
   * input arguments that are ultimately passed to dx.
   */
  @Test
  public void testInputResolverWithMultipleOutputs() throws IOException {
    File primaryOutDir = tmpDir.newFolder("primary-out");
    File primaryOut = new File(primaryOutDir, "primary.jar");
    Set<String> primaryIn = ImmutableSet.of("input/a.jar", "input/b.jar", "input/c.jar");
    File secondaryOutDir = tmpDir.newFolder("secondary-out");
    File secondaryInDir = tmpDir.newFolder("secondary-in");
    File secondaryInFile = new File(secondaryInDir, "2.jar");
    Files.write(new byte[]{0}, secondaryInFile);

    InputResolver resolver = new InputResolver(
        "primary-out/primary.jar",
        primaryIn,
        Optional.of("secondary-out"),
        Optional.of("secondary-in"));
    assertTrue("Expected secondary output", resolver.hasSecondaryOutput());
    final ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot());
    Multimap<File, File> outputToInputs = resolver.createOutputToInputs(DexStore.JAR,
        projectFilesystem);
    assertEquals("Expected 2 output artifacts", 2, outputToInputs.keySet().size());

    MoreAsserts.assertIterablesEquals(
        "Detected inconsistency with primary input arguments",
        Iterables.transform(primaryIn, new Function<String, File>() {
          @Override
          public File apply(String input) {
            return projectFilesystem.getFileForRelativePath(input);
          }
        }),
        outputToInputs.get(primaryOut));

    // Make sure that secondary-out/2.dex.jar came from secondary-in/2.jar.
    File secondaryOutFile = new File(secondaryOutDir,
        SmartDexingStep.transformInputToDexOutput(secondaryInFile, DexStore.JAR));
    MoreAsserts.assertIterablesEquals(
        "Detected inconsistency with secondary output arguments",
        ImmutableSet.of(secondaryInFile),
        outputToInputs.get(secondaryOutFile));
  }

  /**
   * Tests whether pseudo rule cache detection is working properly.
   */
  @Test
  public void testDxPseudoRuleCaching() throws IOException {
    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    File testIn = new File(tmpDir.getRoot(), "testIn");
    ZipOutputStream zipOut = new ZipOutputStream(
        new BufferedOutputStream(new FileOutputStream(testIn)));
    try {
      zipOut.putNextEntry(new ZipEntry("foobar"));
      zipOut.write(new byte[] { 0 });
    } finally {
      zipOut.close();
    }

    File outputFile = tmpDir.newFile("out.dex");
    Path outputHashFile = new File(tmpDir.getRoot(), "out.dex.hash").toPath();
    Files.write("dummy", outputHashFile.toFile(), Charsets.UTF_8);

    DxPseudoRule rule = new DxPseudoRule(context,
        ImmutableSet.of(testIn.toPath()),
        outputFile.getPath(),
        outputHashFile,
        /* optimizeDex */ false);
    assertFalse("'dummy' is not a matching input hash", rule.checkIsCached());

    // Write the real hash into the output hash file and ensure that checkIsCached now
    // yields true.
    String actualHash = rule.hashInputs();
    assertFalse(actualHash.isEmpty());
    Files.write(actualHash, outputHashFile.toFile(), Charsets.UTF_8);

    assertTrue("Matching input hash should be considered cached", rule.checkIsCached());
  }
}
