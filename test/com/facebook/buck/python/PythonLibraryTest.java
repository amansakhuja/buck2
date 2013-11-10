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

package com.facebook.buck.python;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

/**
 * Unit test for {@link PythonLibrary}.
 */
public class PythonLibraryTest {

  @Test
  public void testGetters() {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(
        new BuildTarget("//scripts/python", "foo"));
    ImmutableSortedSet<String> srcs = ImmutableSortedSet.of("");
    PythonLibrary pythonLibrary = new PythonLibrary(
        buildRuleParams,
        srcs);

    assertTrue(pythonLibrary.getProperties().is(LIBRARY));
    assertSame(srcs, pythonLibrary.getPythonSrcs());
  }
}
