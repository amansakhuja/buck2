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

package com.facebook.buck.rules.coercer;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.linkgroup.CxxLinkGroupMappingTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class CxxLinkGroupMappingTargetTraversalCoercerTest {
  private final ProjectFilesystem filesystem = new FakeProjectFilesystem();
  private final Path basePath = Paths.get("java/com/facebook/buck/example");
  private CxxLinkGroupMappingTargetTraversalCoercer coercer;

  public static CxxLinkGroupMappingTargetTraversalCoercer buildTypeCoercer() {
    return new CxxLinkGroupMappingTargetTraversalCoercer();
  }

  @Before
  public void setUp() {
    coercer = buildTypeCoercer();
  }

  @Test
  public void canCoerceTreeTraversalKeyword() throws CoerceFailedException {
    CxxLinkGroupMappingTarget.Traversal traversal =
        coercer.coerce(
            createCellRoots(filesystem),
            filesystem,
            basePath,
            EmptyTargetConfiguration.INSTANCE,
            "tree");
    assertEquals(traversal, CxxLinkGroupMappingTarget.Traversal.TREE);
  }

  @Test
  public void canCoerceNodeTraversalKeyword() throws CoerceFailedException {
    CxxLinkGroupMappingTarget.Traversal traversal =
        coercer.coerce(
            createCellRoots(filesystem),
            filesystem,
            basePath,
            EmptyTargetConfiguration.INSTANCE,
            "node");
    assertEquals(traversal, CxxLinkGroupMappingTarget.Traversal.NODE);
  }
}
