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

import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.util.Map;


public class AndroidResourceRuleTest {

  @Test
  public void testGetInputsToCompareToOutput() {
    // Mock out the traversal of the res/ and assets/ directories. Note that the directory entries
    // are not traversed in alphabetical order because ensuring that a sort happens is part of what
    // we are testing.
    DirectoryTraverser traverser = new DirectoryTraverser() {
      @Override
      public void traverse(DirectoryTraversal traversal) {
        String rootPath = traversal.getRoot().getPath();
        if ("java/src/com/facebook/base/res".equals(rootPath)) {
          traversal.visit(null, "drawable/E.xml");
          traversal.visit(null, "drawable/A.xml");
          traversal.visit(null, "drawable/C.xml");
        } else if ("java/src/com/facebook/base/assets".equals(rootPath)) {
          traversal.visit(null, "drawable/F.xml");
          traversal.visit(null, "drawable/B.xml");
          traversal.visit(null, "drawable/D.xml");
        } else {
          throw new RuntimeException("Unexpected path: " + rootPath);
        }
      }
    };

    // Create an android_library rule with all sorts of input files that it depends on. If any of
    // these files is modified, then this rule should not be cached.
    BuildTarget buildTarget = BuildTargetFactory.newInstance(
        "//java/src/com/facebook/base", "res");
    BuildRuleParams buildRuleParams = new BuildRuleParams(
        buildTarget,
        ImmutableSortedSet.<BuildRule>of() /* deps */,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    AndroidResourceRule androidResourceRule = new AndroidResourceRule(
        buildRuleParams,
        "java/src/com/facebook/base/res",
        "com.facebook",
        "java/src/com/facebook/base/assets",
        "java/src/com/facebook/base/AndroidManifest.xml",
        traverser);

    // Test getInputsToCompareToOutput().
    BuildContext context = null;
    MoreAsserts.assertIterablesEquals(
        "getInputsToCompareToOutput() should return an alphabetically sorted list of all input " +
        "files that contribute to this android_resource() rule.",
        ImmutableList.of(
            "java/src/com/facebook/base/AndroidManifest.xml",
            "java/src/com/facebook/base/assets/drawable/B.xml",
            "java/src/com/facebook/base/assets/drawable/D.xml",
            "java/src/com/facebook/base/assets/drawable/F.xml",
            "java/src/com/facebook/base/res/drawable/A.xml",
            "java/src/com/facebook/base/res/drawable/C.xml",
            "java/src/com/facebook/base/res/drawable/E.xml"),
        androidResourceRule.getInputsToCompareToOutput(context));
  }

  /**
   * Create the following dependency graph of {@link AndroidResourceRule}s:
   * <pre>
   *    A
   *  / | \
   * B  |  D
   *  \ | /
   *    C
   * </pre>
   * Note that an ordinary breadth-first traversal would yield either {@code A B C D} or
   * {@code A D C B}. However, either of these would be <em>wrong</em> in this case because we need
   * to be sure that we perform a topological sort, the resulting traversal of which is either
   * {@code A B D C} or {@code A D B C}.
   * <p>
   * We choose these letters in particular.
   */
  @Test
  public void testGetAndroidResourceDeps() {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    AndroidResourceRule c = AndroidResourceRule.newAndroidResourceRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:c"))
        .setRes("res_c")
        .setRDotJavaPackage("com.facebook")
        .build(buildRuleIndex);
    buildRuleIndex.put(c.getFullyQualifiedName(), c);

    AndroidResourceRule b = AndroidResourceRule.newAndroidResourceRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:b"))
        .setRes("res_b")
        .setRDotJavaPackage("com.facebook")
        .addDep("//:c")
        .build(buildRuleIndex);
    buildRuleIndex.put(b.getFullyQualifiedName(), b);

    AndroidResourceRule d = AndroidResourceRule.newAndroidResourceRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:d"))
        .setRes("res_d")
        .setRDotJavaPackage("com.facebook")
        .addDep("//:c")
        .build(buildRuleIndex);
    buildRuleIndex.put(d.getFullyQualifiedName(), d);

    AndroidResourceRule a = AndroidResourceRule.newAndroidResourceRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:a"))
        .setRes("res_a")
        .setRDotJavaPackage("com.facebook")
        .addDep("//:b")
        .addDep("//:c")
        .addDep("//:d")
        .build(buildRuleIndex);
    buildRuleIndex.put(a.getFullyQualifiedName(), a);

    DependencyGraph graph = RuleMap.createGraphFromBuildRules(buildRuleIndex);
    ImmutableList<HasAndroidResourceDeps> deps = UberRDotJavaUtil.getAndroidResourceDeps(a, graph);

    // Note that a topological sort for a DAG is not guaranteed to be unique. In this particular
    // case, there are two possible valid outcomes.
    ImmutableList<AndroidResourceRule> validResult1 = ImmutableList.of(a, b, d, c);
    ImmutableList<AndroidResourceRule> validResult2 = ImmutableList.of(a, d, b, c);

    assertTrue(
        String.format(
            "Topological sort %s should be either %s or %s", deps, validResult1, validResult2),
        deps.equals(validResult1) || deps.equals(validResult2));

    // Introduce an AndroidBinaryRule that depends on A and C and verify that the same topological
    // sort results. This verifies that both AndroidResourceRule.getAndroidResourceDeps does the
    // right thing when it gets a non-AndroidResourceRule as well as an AndroidResourceRule.
    AndroidBinaryRule e = AndroidBinaryRule.newAndroidBinaryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//:e"))
        .setManifest("AndroidManfiest.xml")
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystorePropertiesPath("debug.keystore")
        .addDep("//:a")
        .addDep("//:c")
        .build(buildRuleIndex);
    buildRuleIndex.put(e.getFullyQualifiedName(), e);

    DependencyGraph graph2 = RuleMap.createGraphFromBuildRules(buildRuleIndex);
    ImmutableList<HasAndroidResourceDeps> deps2 = UberRDotJavaUtil.getAndroidResourceDeps(e, graph2);
    assertTrue(
        String.format(
            "Topological sort %s should be either %s or %s", deps, validResult1, validResult2),
            deps2.equals(validResult1) || deps2.equals(validResult2));
  }
}
