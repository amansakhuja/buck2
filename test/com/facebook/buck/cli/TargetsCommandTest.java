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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.cli.TargetsCommand.TargetsCommandPredicate;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.java.DefaultJavaLibraryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.JavaTestRule;
import com.facebook.buck.java.PrebuiltJarRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

public class TargetsCommandTest {

  private final String projectRootPath = ".";
  private final File projectRoot = new File(projectRootPath);
  private final Ansi ansi = Ansi.withoutTty();
  private CapturingPrintStream stdOutStream;
  private CapturingPrintStream stdErrStream;
  private TargetsCommand targetsCommand;


  private SortedMap<String, BuildRule> buildBuildTargets(String buildFile,
      String outputFile,
      String name) {
    SortedMap<String, BuildRule> buildRules = Maps.newTreeMap();
    String baseName = "//";
    BuildTarget buildTarget = new BuildTarget(new File(buildFile), baseName, name);
    FakeBuildRule buildRule = new FakeBuildRule(BuildRuleType.JAVA_LIBRARY,
        buildTarget,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());
    buildRule.setOutputFile(new File(outputFile));

    buildRules.put(buildTarget.getFullyQualifiedName(), buildRule);
    return buildRules;
  }

  private String testDataPath(String fileName) {
    return "testdata/com/facebook/buck/cli/" + fileName;
  }

  @Before
  public void setUp() {
    stdOutStream = new CapturingPrintStream();
    stdErrStream = new CapturingPrintStream();
    Console console = new Console(stdOutStream, stdErrStream, ansi);
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectRoot);
    KnownBuildRuleTypes buildRuleTypes = new KnownBuildRuleTypes();
    ArtifactCache artifactCache = new NoopArtifactCache();
    targetsCommand =
        new TargetsCommand(new CommandRunnerParams(
            console,
            projectFilesystem,
            buildRuleTypes,
            artifactCache));
  }

  @Test
  public void testJsonOutputForBuildTarget() throws IOException {
    final String testBuckFile1 = testDataPath("TargetsCommandTestBuckFile1.txt");
    final String testBuckFileJson1 = testDataPath("TargetsCommandTestBuckJson1.js");
    final String outputFile = "buck-gen/test/outputFile";
    JsonFactory jsonFactory = new JsonFactory();
    ObjectMapper mapper = new ObjectMapper();

    // run `buck targets` on the build file and parse the observed JSON.
    SortedMap<String, BuildRule> buildRules = buildBuildTargets(testBuckFile1,
        outputFile,
        "test-library");

    targetsCommand.printJsonForTargets(buildRules, /* includes */ ImmutableList.<String>of());
    String observedOutput = stdOutStream.getContentsAsString(Charsets.UTF_8);
    JsonNode observed = mapper.readTree(jsonFactory.createJsonParser(observedOutput));

    // parse the expected JSON.
    String expectedJson = Files.toString(new File(testBuckFileJson1), Charsets.UTF_8)
        .replace("{$OUTPUT_FILE}", outputFile);
    JsonNode expected = mapper.readTree(jsonFactory.createJsonParser(expectedJson)
        .enable(Feature.ALLOW_COMMENTS));

    assertEquals("Output from targets command should match expected JSON.", expected, observed);
    assertEquals("Nothing should be printed to stderr.",
        "",
        stdErrStream.getContentsAsString(Charsets.UTF_8));
  }

  @Test
  public void testNormalOutputForBuildTarget() throws IOException {
    final String testBuckFile1 = testDataPath("TargetsCommandTestBuckFile1.txt");
    final String outputFile = "buck-out/gen/test/outputFile";

    // run `buck targets` on the build file and parse the observed JSON.
    SortedMap<String, BuildRule> buildRules = buildBuildTargets(testBuckFile1,
        outputFile,
        "test-library");

    targetsCommand.printTargetsList(buildRules, /* showOutput */ false);
    String observedOutput = stdOutStream.getContentsAsString(Charsets.UTF_8);

    assertEquals("Output from targets command should match expected output.",
        "//:test-library",
        observedOutput.trim());
    assertEquals("Nothing should be printed to stderr.",
        "",
        stdErrStream.getContentsAsString(Charsets.UTF_8));
  }

  @Test
  public void testNormalOutputForBuildTargetWithOutput() throws IOException {
    final String testBuckFile1 = testDataPath("TargetsCommandTestBuckFile1.txt");
    final String outputFile = "buck-out/gen/test/outputFile";

    // run `buck targets` on the build file and parse the observed JSON.
    SortedMap<String, BuildRule> buildRules = buildBuildTargets(testBuckFile1,
        outputFile,
        "test-library");

    targetsCommand.printTargetsList(buildRules, /* showOutput */ true);
    String observedOutput = stdOutStream.getContentsAsString(Charsets.UTF_8);

    assertEquals("Output from targets command should match expected output.",
        "//:test-library " + outputFile,
        observedOutput.trim());
    assertEquals("Nothing should be printed to stderr.",
        "",
        stdErrStream.getContentsAsString(Charsets.UTF_8));
  }

  @Test
  public void testJsonOutputForMissingBuildTarget() throws IOException {
    final String testBuckFile1 = testDataPath("TargetsCommandTestBuckFile1.txt");
    final String outputFile = "buck-gen/test/outputFile";

    // nonexistent target should not exist.
    SortedMap<String, BuildRule> buildRules = buildBuildTargets(testBuckFile1,
        outputFile, "nonexistent");
    targetsCommand.printJsonForTargets(buildRules, /* includes */ ImmutableList.<String>of());

    String output = stdOutStream.getContentsAsString(Charset.defaultCharset());
    assertEquals("[\n]\n", output);
    assertEquals("BUILD FAILED: unable to find rule for target //:nonexistent\n",
        stdErrStream.getContentsAsString(Charsets.UTF_8));
  }

  @Test
  public void testValidateBuildTargetForNonAliasTarget()
      throws IOException, NoSuchBuildTargetException {
    // Set up the test buck file, parser, config, options.
    final String testBuckFile = testDataPath("TargetsCommandTestBuckFile1.txt");
    BuildTargetParser parser = EasyMock.createMock(BuildTargetParser.class);
    EasyMock.expect(parser.parse("//:test-library", ParseContext.fullyQualified()))
        .andReturn(new BuildTarget(new File(testBuckFile), "//", "test-library"))
        .anyTimes();
    EasyMock.expect(parser.parse("//:", ParseContext.fullyQualified()))
        .andThrow(new BuildTargetParseException(
            String.format("%s cannot end with a colon.", "//:")))
        .anyTimes();
    EasyMock.expect(parser.parse("//blah/foo:bar", ParseContext.fullyQualified()))
        .andThrow(EasyMock.createMock(NoSuchBuildTargetException.class))
        .anyTimes();
    EasyMock.expect(parser.parse("//:test-libarry", ParseContext.fullyQualified()))
        .andReturn(new BuildTarget(new File(testBuckFile), "//", "test-libarry"))
        .anyTimes();
    EasyMock.replay(parser);
    Reader reader = new StringReader("");
    BuckConfig config = BuckConfig.createFromReader(reader, parser);
    TargetsCommandOptions options = new TargetsCommandOptions(config);

    // Test a valid target.
    assertEquals(
        "//:test-library",
        targetsCommand.validateBuildTargetForFullyQualifiedTarget("//:test-library", options));

    // Targets that will be rejected by BuildTargetParser with an exception.
    try {
      targetsCommand.validateBuildTargetForFullyQualifiedTarget("//:", options);
      fail("Should have thrown BuildTargetParseException.");
    } catch (BuildTargetParseException e) {
      assertEquals("//: cannot end with a colon.", e.getHumanReadableErrorMessage());
    }
    assertNull(targetsCommand.validateBuildTargetForFullyQualifiedTarget(
        "//blah/foo:bar", options));

    // Should pass BuildTargetParser but validateBuildTargetForNonAliasTarget will return null.
    assertNull(targetsCommand.validateBuildTargetForFullyQualifiedTarget(
        "//:test-libarry", options));
  }

  private PartialGraph createGraphFromBuildRules(Iterable<BuildRule> rules, List<String> targets) {
    MutableDirectedGraph<BuildRule> graph = new MutableDirectedGraph<BuildRule>();
    for (BuildRule rule : rules) {
      for (BuildRule dep : rule.getDeps()) {
        graph.addEdge(rule, dep);
      }
    }

    List<BuildTarget> buildTargets = Lists.transform(targets, new Function<String, BuildTarget>() {
      @Override
      public BuildTarget apply(String target) {
        return BuildTargetFactory.newInstance(target);
      }
    });

    DependencyGraph dependencyGraph = new DependencyGraph(graph);
    return PartialGraphFactory.newInstance(dependencyGraph, buildTargets);
  }

  @Test
  public void testGetMachingBuildTargets() throws CmdLineException, IOException {
    Map<String, BuildRule> buildRuleIndex = Maps.newHashMap();
    ArtifactCache artifactCache = new NoopArtifactCache();
    PrebuiltJarRule emptyRule = PrebuiltJarRule.newPrebuiltJarRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//empty:empty"))
        .setBinaryJar("")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .setArtifactCache(artifactCache)
        .build(buildRuleIndex);
    buildRuleIndex.put(emptyRule.getFullyQualifiedName(), emptyRule);
    JavaLibraryRule javaLibraryRule = DefaultJavaLibraryRule.newJavaLibraryRuleBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//javasrc:java-library"))
        .addSrc("javasrc/JavaLibrary.java")
        .addVisibilityPattern(BuildTargetPattern.MATCH_ALL)
        .addDep("//empty:empty")
        .setArtifactCache(artifactCache)
        .build(buildRuleIndex);
    buildRuleIndex.put(javaLibraryRule.getFullyQualifiedName(), javaLibraryRule);
    JavaTestRule javaTestRule =
        JavaTestRule.newJavaTestRuleBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//javatest:test-java-library"))
            .addSrc("javatest/TestJavaLibrary.java")
            .addDep("//javasrc:java-library")
            .setArtifactCache(artifactCache)
            .build(buildRuleIndex);
    buildRuleIndex.put(javaTestRule.getFullyQualifiedName(), javaTestRule);

    List<String> targets = Lists.newArrayList();
    targets.add("//empty:empty");
    targets.add("//javasrc:java-library");
    targets.add("//javatest:test-java-library");

    PartialGraph graph = createGraphFromBuildRules(buildRuleIndex.values(), targets);
    ImmutableSet<BuildRuleType> buildRuleTypes = ImmutableSet.of();

    ImmutableSet<String> referencedFiles;
    ImmutableSet<BuildTarget> targetBuildRules = ImmutableSet.of();

    // No target depends on the referenced file.
    referencedFiles = ImmutableSet.of("excludesrc/CannotFind.java");
    SortedMap<String, BuildRule> matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            graph.getDependencyGraph(),
            new TargetsCommandPredicate(graph, buildRuleTypes, referencedFiles, targetBuildRules));
    assertTrue(matchingBuildRules.isEmpty());

    // Only test-android-library target depends on the referenced file.
    referencedFiles = ImmutableSet.of("javatest/TestJavaLibrary.java");
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            graph.getDependencyGraph(),
            new TargetsCommandPredicate(graph, buildRuleTypes, referencedFiles, targetBuildRules));
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library"),
        matchingBuildRules.keySet());

    // The test-android-library target indirectly depend on the referenced file,
    // while test-java-library target directly depend on the referenced file.
    referencedFiles = ImmutableSet.of("javasrc/JavaLibrary.java");
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            graph.getDependencyGraph(),
            new TargetsCommandPredicate(graph, buildRuleTypes, referencedFiles, targetBuildRules));
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library", "//javasrc:java-library"),
        matchingBuildRules.keySet());

    // Output target only need to depend on one referenced file.
    referencedFiles = ImmutableSet.of(
        "javatest/TestJavaLibrary.java", "othersrc/CannotFind.java");
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            graph.getDependencyGraph(),
            new TargetsCommandPredicate(graph, buildRuleTypes, referencedFiles, targetBuildRules));
    assertEquals(
        ImmutableSet.of("//javatest:test-java-library"),
        matchingBuildRules.keySet());

    // If no referenced file, means this filter is disabled, we can find all targets.
    referencedFiles = null;
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            graph.getDependencyGraph(),
            new TargetsCommandPredicate(graph,
                buildRuleTypes,
                ImmutableSet.<String>of(),
                targetBuildRules));
    assertEquals(
        ImmutableSet.of(
            "//javatest:test-java-library",
            "//javasrc:java-library",
            "//empty:empty"),
        matchingBuildRules.keySet());

    // Specify java_test, java_library as type filters.
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            graph.getDependencyGraph(),
            new TargetsCommandPredicate(
                graph,
                ImmutableSet.of(BuildRuleType.JAVA_TEST, BuildRuleType.JAVA_LIBRARY),
                ImmutableSet.<String>of(),
                targetBuildRules));
    assertEquals(
        ImmutableSet.of(
            "//javatest:test-java-library",
            "//javasrc:java-library"),
        matchingBuildRules.keySet());


    // Specify java_test, java_library, and a rule name as type filters.
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            graph.getDependencyGraph(),
            new TargetsCommandPredicate(
                graph,
                ImmutableSet.of(BuildRuleType.JAVA_TEST, BuildRuleType.JAVA_LIBRARY),
                ImmutableSet.<String>of(),
                ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))));
    assertEquals(
        ImmutableSet.of("//javasrc:java-library"), matchingBuildRules.keySet());

    // Only filter by BuildTarget
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            graph.getDependencyGraph(),
            new TargetsCommandPredicate(
                graph,
                ImmutableSet.<BuildRuleType>of(),
                ImmutableSet.<String>of(),
                ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))));
    assertEquals(
        ImmutableSet.of("//javasrc:java-library"), matchingBuildRules.keySet());


    // Filter by BuildTarget and Referenced Files
    matchingBuildRules =
        targetsCommand.getMatchingBuildRules(
            graph.getDependencyGraph(),
            new TargetsCommandPredicate(
                graph,
                ImmutableSet.<BuildRuleType>of(),
                ImmutableSet.of("javatest/TestJavaLibrary.java"),
                ImmutableSet.of(BuildTargetFactory.newInstance("//javasrc:java-library"))));
    assertEquals(
        ImmutableSet.<String>of(), matchingBuildRules.keySet());

  }
}
