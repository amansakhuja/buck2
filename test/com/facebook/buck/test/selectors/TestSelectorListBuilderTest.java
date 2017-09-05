/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.test.selectors;

import static org.junit.Assert.*;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Testing parsing of test selectors to be used by TestCommand */
public class TestSelectorListBuilderTest {
  @Test
  public void addRawSelectors() throws Exception {
    final TestSelectorList.Builder builder = TestSelectorList.builder();
    builder.addRawSelectors("com.foo.Foo");
    builder.addRawSelectors("!com.bar.Bar");
    final TestSelectorList list = builder.build();

    assertTrue(list.possiblyIncludesClassName("com.foo.Foo"));
    assertFalse(list.possiblyIncludesClassName("com.bar.Bar"));
  }

  @Test
  public void addFileSelectors() throws Exception {
    final TestSelectorList.Builder builder = TestSelectorList.builder();
    final Path tempFile = Files.createTempFile("test", "selectors");
    try (BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
      writer.write("com.foo.Foo\n!com.bar.Bar");
    }
    builder.addRawSelectors(":" + tempFile.toString());
    final TestSelectorList list = builder.build();

    assertTrue(list.possiblyIncludesClassName("com.foo.Foo"));
    assertFalse(list.possiblyIncludesClassName("com.bar.Bar"));
  }
}
