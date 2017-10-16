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

package com.facebook.buck.apple.clang;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ModuleMapTest {

  @Test
  public void testNoSwift() {
    ModuleMap testMap = new ModuleMap("TestModule", ModuleMap.SwiftMode.NO_SWIFT);
    assertEquals(
        "module TestModule {\n"
            + "    umbrella header \"TestModule.h\"\n"
            + "\n"
            + "    export *\n"
            + "    module * { export * }\n"
            + "}\n"
            + "\n",
        testMap.render());
  }

  @Test
  public void testIncludeSwift() {
    ModuleMap testMap = new ModuleMap("TestModule", ModuleMap.SwiftMode.INCLUDE_SWIFT_HEADER);
    assertEquals(
        "module TestModule {\n"
            + "    umbrella header \"TestModule.h\"\n"
            + "\n"
            + "    export *\n"
            + "    module * { export * }\n"
            + "}\n"
            + "\n"
            + "module TestModule.Swift {\n"
            + "    header \"TestModule-Swift.h\"\n"
            + "    requires objc\n"
            + "}"
            + "\n",
        testMap.render());
  }

  @Test
  public void testExcludeSwift() {
    ModuleMap testMap = new ModuleMap("TestModule", ModuleMap.SwiftMode.EXCLUDE_SWIFT_HEADER);
    assertEquals(
        "module TestModule {\n"
            + "    umbrella header \"TestModule.h\"\n"
            + "\n"
            + "    export *\n"
            + "    module * { export * }\n"
            + "}\n"
            + "\n"
            + "module TestModule.__Swift {\n"
            + "    exclude header \"TestModule-Swift.h\"\n"
            + "}"
            + "\n",
        testMap.render());
  }
}
