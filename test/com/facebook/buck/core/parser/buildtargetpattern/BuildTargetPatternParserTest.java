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

package com.facebook.buck.core.parser.buildtargetpattern;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern.Kind;
import com.facebook.buck.core.path.ForwardRelativePath;
import java.util.Optional;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class BuildTargetPatternParserTest {

  private CellNameResolver cellNameResolver = TestCellNameResolver.forRoot("a", "cell");

  @Rule public ExpectedException exception = ExpectedException.none();

  @SuppressWarnings("unused")
  private Object[] dataParseSuccess() {
    return new Object[] {
      new Object[] {
        "cell//path/to:target",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(
                CanonicalCellName.unsafeOf(Optional.of("cell")), ForwardRelativePath.of("path/to")),
            Kind.SINGLE,
            "target")
      },
      new Object[] {
        "//path/to:target",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of("path/to")),
            Kind.SINGLE,
            "target")
      },
      new Object[] {
        "//path/to",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of("path/to")),
            Kind.SINGLE,
            "to")
      },
      new Object[] {
        "cell//path/to",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(
                CanonicalCellName.unsafeOf(Optional.of("cell")), ForwardRelativePath.of("path/to")),
            Kind.SINGLE,
            "to")
      },
      new Object[] {
        "//root",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of("root")),
            Kind.SINGLE,
            "root")
      },
      new Object[] {
        "//:target",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of("")),
            Kind.SINGLE,
            "target")
      },
      new Object[] {
        "cell//path/to/...",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(
                CanonicalCellName.unsafeOf(Optional.of("cell")), ForwardRelativePath.of("path/to")),
            Kind.RECURSIVE,
            "")
      },
      new Object[] {
        "//path/to/...",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of("path/to")),
            Kind.RECURSIVE,
            "")
      },
      new Object[] {
        "//...",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of("")),
            Kind.RECURSIVE,
            "")
      },
      new Object[] {
        "cell//...",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(
                CanonicalCellName.unsafeOf(Optional.of("cell")), ForwardRelativePath.of("")),
            Kind.RECURSIVE,
            "")
      },
      new Object[] {
        "//path/to:",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of("path/to")),
            Kind.PACKAGE,
            "")
      },
      new Object[] {
        "cell//path/to:",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(
                CanonicalCellName.unsafeOf(Optional.of("cell")), ForwardRelativePath.of("path/to")),
            Kind.PACKAGE,
            "")
      },
      new Object[] {
        "//:",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of("")),
            Kind.PACKAGE,
            "")
      },
      new Object[] {
        "a//b:c",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(
                CanonicalCellName.unsafeOf(Optional.of("a")), ForwardRelativePath.of("b")),
            Kind.SINGLE,
            "c")
      },
      new Object[] {
        "a//b",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(
                CanonicalCellName.unsafeOf(Optional.of("a")), ForwardRelativePath.of("b")),
            Kind.SINGLE,
            "b")
      },
      new Object[] {
        "//a",
        ImmutableBuildTargetPattern.of(
            CellRelativePath.of(CanonicalCellName.rootCell(), ForwardRelativePath.of("a")),
            Kind.SINGLE,
            "a")
      },
    };
  }

  @Test
  @Parameters(method = "dataParseSuccess")
  @TestCaseName("parsingSucceeds({0})")
  public void parsingSucceeds(String pattern, BuildTargetPattern expected)
      throws BuildTargetParseException {
    assertEquals(expected, BuildTargetPatternParser.parse(pattern, cellNameResolver));
  }

  @Test
  @Parameters({
    "",
    "path/to:target",
    "//",
    "///",
    "...",
    ":",
    "/",
    "/:",
    "/...",
    "///some/path:target",
    "//path/to...",
    "//path/:...",
    "//a/b//c/d:f",
    "//a/b/"
  })
  @TestCaseName("parsingFails({0})")
  public void parsingFails(String pattern) throws BuildTargetParseException {
    exception.expect(BuildTargetParseException.class);
    BuildTargetPatternParser.parse(pattern, cellNameResolver);
  }
}
