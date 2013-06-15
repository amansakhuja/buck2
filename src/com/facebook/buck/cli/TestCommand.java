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

import com.facebook.buck.command.Build;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.java.GenerateCodeCoverageReportStep;
import com.facebook.buck.java.InstrumentStep;
import com.facebook.buck.java.JUnitStep;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.JavaTestRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.RawRulePredicate;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.TestCaseSummary;
import com.facebook.buck.rules.TestResults;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;
import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class TestCommand extends AbstractCommandRunner<TestCommandOptions> {

  public TestCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
  TestCommandOptions createOptions(BuckConfig buckConfig) {
    return new TestCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptions(final TestCommandOptions options) throws IOException {
    // If the user asked to run all of the tests, use a special method for that that is optimized to
    // parse all of the build files and traverse the dependency graph to find all of the tests to
    // run.
    if (options.isRunAllTests()) {
      try {
        return runAllTests(options);
      } catch (NoSuchBuildTargetException e) {
        console.printFailureWithoutStacktrace(e);
        return 1;
      }
    }

    BuildCommand buildCommand = new BuildCommand(getCommandRunnerParams());

    int exitCode = buildCommand.runCommandWithOptions(options);
    if (exitCode != 0) {
      return exitCode;
    }

    Build build = buildCommand.getBuild();

    Iterable<TestRule> results = getCandidateRulesByIncludedLabels(
        build.getDependencyGraph(), options.getIncludedLabels());

    results = filterTestRules(options, results);

    BuildContext buildContext = build.getBuildContext();
    ExecutionContext executionContext = build.getExecutionContext();
    StepRunner stepRunner = build.getCommandRunner();
    return runTests(results, buildContext, executionContext, stepRunner, options);
  }

  /**
   * Returns the ShellCommand object that is supposed to instrument the class files that the list
   * of tests is supposed to be testing. From TestRule objects, we derive the class file folders
   * and generate a EMMA instr shell command object, which can run in a CommandRunner.
   */
  private Step getInstrumentCommand(
      ImmutableSet<JavaLibraryRule> rulesUnderTest) {
    ImmutableSet.Builder<String> pathsToInstrumentedClasses = ImmutableSet.builder();

    // Add all class directories of java libraries that we are testing to -instrpath.
    for (JavaLibraryRule path : rulesUnderTest) {
      File output = path.getOutput();
      if (output == null) {
        continue;
      }
      String classDirectory = output.getAbsolutePath();
      pathsToInstrumentedClasses.add(classDirectory);
    }

    // Run EMMA instrumentation. This will instrument the classes we generated in the build command.
    // TODO(user): Output instrumented class files in different folder and change junit classdir.
    return new InstrumentStep("overwrite", pathsToInstrumentedClasses.build());
  }

  /**
   * Returns the ShellCommand object that is supposed to generate a code coverage report from data
   * obtained during the test run. This method will also generate a set of source paths to the class
   * files tested during the test run.
   */
  private Step getReportCommand(
      ImmutableSet<JavaLibraryRule> rulesUnderTest,
      Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional,
      ProjectFilesystem projectFilesystem) {
    ImmutableSet.Builder<String> srcDirectories = ImmutableSet.builder();

    // Add all source directories of java libraries that we are testing to -sourcepath.
    for (JavaLibraryRule rule : rulesUnderTest) {
      ImmutableSet<String> sourceFolderPath =
          getPathToSourceFolders(rule, defaultJavaPackageFinderOptional, projectFilesystem);
      if (!sourceFolderPath.isEmpty()) {
        srcDirectories.addAll(sourceFolderPath);
      }
    }

    return new GenerateCodeCoverageReportStep(srcDirectories.build(),
        JUnitStep.EMMA_OUTPUT_DIR);
  }

  /**
   * Returns a set of source folders of the java files of a library.
   */
  @VisibleForTesting
  static ImmutableSet<String> getPathToSourceFolders(
      JavaLibraryRule rule,
      Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional,
      ProjectFilesystem projectFilesystem) {
    ImmutableSet<String> javaSrcPaths = rule.getJavaSrcs();

    // A Java library rule with just resource files has an empty javaSrcPaths.
    if (javaSrcPaths.isEmpty()) {
      return ImmutableSet.of();
    }

    // If defaultJavaPackageFinderOptional is not present, then it could mean that there was an
    // error reading from the buck configuration file.
    if (!defaultJavaPackageFinderOptional.isPresent()) {
      throw new HumanReadableException(
          "Please include a [java] section with src_root property in the .buckconfig file.");
    }

    DefaultJavaPackageFinder defaultJavaPackageFinder = defaultJavaPackageFinderOptional.get();

    // Iterate through all source paths to make sure we are generating a complete set of source
    // folders for the source paths.
    Set<String> srcFolders = Sets.newHashSet();
    loopThroughSourcePath:
    for (String javaSrcPath : javaSrcPaths) {
      if (!JavaTestRule.isGeneratedFile(javaSrcPath)) {

        // If the source path is already under a known source folder, then we can skip this
        // source path.
        for (String srcFolder : srcFolders) {
          if (javaSrcPath.startsWith(srcFolder)) {
            continue loopThroughSourcePath;
          }
        }

        // If the source path is under one of the source roots, then we can just add the source
        // root.
        ImmutableSortedSet<String> pathsFromRoot = defaultJavaPackageFinder.getPathsFromRoot();
        for (String root : pathsFromRoot) {
          if (javaSrcPath.startsWith(root)) {
            srcFolders.add(root);
            continue loopThroughSourcePath;
          }
        }

        // Traverse the file system from the parent directory of the java file until we hit the
        // parent of the src root directory.
        ImmutableSet<String> pathElements = defaultJavaPackageFinder.getPathElements();
        File directory = projectFilesystem.getFileForRelativePath(javaSrcPath).getParentFile();
        while (directory != null && !pathElements.contains(directory.getName())) {
          directory = directory.getParentFile();
        }

        if (directory != null) {
          String directoryPath = directory.getPath();
          if (!directoryPath.endsWith("/")) {
            directoryPath += "/";
          }
          srcFolders.add(directoryPath);
        }
      }
    }

    return ImmutableSet.copyOf(srcFolders);
  }

  private int runAllTests(TestCommandOptions options) throws IOException,
      NoSuchBuildTargetException {
    Logging.setLoggingLevelForVerbosity(options.getVerbosity());

    // The first step is to parse all of the build files. This will populate the parser and find all
    // of the test rules.
    RawRulePredicate predicate = new RawRulePredicate() {
      @Override
      public boolean isMatch(
          Map<String, Object> rawParseData,
          BuildRuleType buildRuleType,
          BuildTarget buildTarget) {
        return buildRuleType.isTestRule();
      }
    };
    PartialGraph partialGraph = PartialGraph.createPartialGraph(predicate,
        getProjectFilesystem(),
        options.getDefaultIncludes(),
        getParser());

    final DependencyGraph graph = partialGraph.getDependencyGraph();

    // Look up all of the test rules in the dependency graph.
    Iterable<TestRule> testRules = Iterables.transform(partialGraph.getTargets(),
        new Function<BuildTarget, TestRule>() {
      @Override public TestRule apply(BuildTarget buildTarget) {
        return (TestRule)graph.findBuildRuleByTarget(buildTarget);
      }
    });

    testRules = filterTestRules(options, testRules);

    // Build all of the test rules.
    Build build = options.createBuild(graph, getProjectFilesystem(), getArtifactCache(), console);
    int exitCode = BuildCommand.executeBuildAndPrintAnyFailuresToConsole(build, console);
    if (exitCode != 0) {
      return exitCode;
    }

    // Once all of the rules are built, then run the tests.
    return runTests(testRules,
        build.getBuildContext(),
        build.getExecutionContext(),
        build.getCommandRunner(),
        options);
  }

  @VisibleForTesting
  static Iterable<TestRule> getCandidateRulesByIncludedLabels(
      DependencyGraph graph, final ImmutableSet<String> includedLabels) {
    AbstractBottomUpTraversal<BuildRule, List<TestRule>> traversal =
        new AbstractBottomUpTraversal<BuildRule, List<TestRule>>(graph) {

      private final List<TestRule> results = Lists.newArrayList();

      @Override
      public void visit(BuildRule buildRule) {
        if (buildRule instanceof TestRule) {
          TestRule testRule = (TestRule)buildRule;
          // If includedSet not empty, only select test rules that contain included label.
          if (includedLabels.isEmpty() ||
              !Sets.intersection(testRule.getLabels(), includedLabels).isEmpty()) {
            results.add(testRule);
          }
        }
      }

      @Override
      public List<TestRule> getResult() {
        return results;
      }
    };
    traversal.traverse();
    return traversal.getResult();
  }

  @VisibleForTesting
  static Iterable<TestRule> filterTestRules(final TestCommandOptions options,
      Iterable<TestRule> testRules) {
    // Filter out all test rules that contain labels we've excluded.
    return Iterables.filter(testRules, new Predicate<TestRule>() {
      @Override public boolean apply(TestRule rule) {
        return Sets.intersection(rule.getLabels(), options.getExcludedLabels()).isEmpty();
      }
    });
  }

  private int runTests(
      Iterable<TestRule> tests,
      BuildContext buildContext,
      ExecutionContext executionContext,
      StepRunner stepRunner,
      TestCommandOptions options) throws IOException {
    ImmutableSet<JavaLibraryRule> rulesUnderTest;
    // If needed, we first run instrumentation on the class files.
    if (options.isCodeCoverageEnabled()) {
      rulesUnderTest = getRulesUnderTest(tests);
      if (!rulesUnderTest.isEmpty()) {
        try {
          stepRunner.runStep(
              new MakeCleanDirectoryStep(JUnitStep.EMMA_OUTPUT_DIR));
          stepRunner.runStep(getInstrumentCommand(rulesUnderTest));
        } catch (StepFailedException e) {
          console.printFailureWithoutStacktrace(e);
          return 1;
        }
      }
    } else {
      rulesUnderTest = ImmutableSet.of();
    }

    // Inform the user that we are now running the tests.
    String targetsBeingTested;
    if (options.isRunAllTests()) {
      targetsBeingTested = "ALL TESTS";
    } else {
      targetsBeingTested = Joiner.on(' ').join(options.getArgumentsFormattedAsBuildTargets());
    }
    getStdErr().printf("TESTING %s\n", targetsBeingTested);

    // Start running all of the tests. The result of each java_test() rule is represented as a
    // ListenableFuture.
    List<ListenableFuture<TestResults>> results = Lists.newArrayList();
    for (TestRule test : tests) {
      List<Step> steps;

      // Determine whether the test needs to be executed.
      boolean isTestRunRequired = isTestRunRequiredForTest(test, buildContext, executionContext);
      if (isTestRunRequired) {
        steps = test.runTests(buildContext, executionContext);
      } else {
        steps = ImmutableList.of();
      }

      // Always run the commands, even if the list of commands as empty. There may be zero commands
      // because the rule is cached, but its results must still be processed.
      ListenableFuture<TestResults> testResults =
          stepRunner.runStepsAndYieldResult(steps,
              test.interpretTestResults(),
              test.getBuildTarget());
      results.add(testResults);
    }

    // Block until all the tests have finished running.
    ListenableFuture<List<TestResults>> uberFuture = Futures.allAsList(results);
    List<TestResults> completedResults;
    try {
      completedResults = uberFuture.get();
    } catch (InterruptedException e) {
      e.printStackTrace(getStdErr());
      return 1;
    } catch (ExecutionException e) {
      e.printStackTrace(getStdErr());
      return 1;
    }

    // Write out the results as XML, if requested.
    if (options.getPathToXmlTestOutput() != null) {
      writeXmlOutput(completedResults, options.getPathToXmlTestOutput());
    }

    // Print whether each test succeeded or failed.
    boolean isAllTestsPassed = true;
    int numFailures = 0;
    Ansi ansi = console.getAnsi();
    for (TestResults summary : completedResults) {
      if (!summary.isSuccess()) {
        isAllTestsPassed = false;
        numFailures += summary.getFailureCount();
      }
      getStdErr().print(summary.getSummaryWithFailureDetails(ansi));
    }

    // Print the summary of the test results.
    if (completedResults.isEmpty()) {
      ansi.printlnHighlightedFailureText(getStdErr(), "NO TESTS RAN");
    } else if (isAllTestsPassed) {
      ansi.printlnHighlightedSuccessText(getStdErr(), "TESTS PASSED");
    } else {
      ansi.printlnHighlightedFailureText(getStdErr(),
          String.format("TESTS FAILED: %d Failures", numFailures));
    }

    // Generate the code coverage report.
    if (options.isCodeCoverageEnabled() && !rulesUnderTest.isEmpty()) {
      try {
        Optional<DefaultJavaPackageFinder> defaultJavaPackageFinderOptional =
            options.getJavaPackageFinder();
        stepRunner.runStep(
            getReportCommand(rulesUnderTest, defaultJavaPackageFinderOptional, getProjectFilesystem()));
      } catch (StepFailedException e) {
        console.printFailureWithoutStacktrace(e);
        return 1;
      }
    }

    return isAllTestsPassed ? 0 : 1;
  }

  @VisibleForTesting
  static boolean isTestRunRequiredForTest(TestRule test,
      BuildContext buildContext, ExecutionContext executionContext) {
    boolean isTestRunRequired;
    BuildRuleSuccess.Type successType;
    if (executionContext.isDebugEnabled()) {
      // If debug is enabled, then we should always run the tests as the user is expecting to
      // hook up a debugger.
      isTestRunRequired = true;
    } else if (((successType = test.getBuildResultType()) != null)
               && (successType == BuildRuleSuccess.Type.FETCHED_FROM_CACHE
                      || successType == BuildRuleSuccess.Type.MATCHING_RULE_KEY)
               && test.hasTestResultFiles(buildContext)) {
      // If this build rule's artifacts (which includes the rule's output and its test result
      // files) are up to date, then no commands are necessary to run the tests. The test result
      // files will be read from the XML files in interpretTestResults().
      isTestRunRequired = false;
    } else {
      isTestRunRequired = true;
    }
    return isTestRunRequired;
  }

  /**
   * Generates the set of rules under test.
   */
  private ImmutableSet<JavaLibraryRule> getRulesUnderTest(Iterable<TestRule> tests) {
    ImmutableSet.Builder<JavaLibraryRule> rulesUnderTest = ImmutableSet.builder();

    // Gathering all rules whose source will be under test.
    for (TestRule test : tests) {
      if (test instanceof JavaTestRule) {
        JavaTestRule javaTestRule = (JavaTestRule) test;
        rulesUnderTest.addAll(javaTestRule.getSourceUnderTest());
      }
    }

    return rulesUnderTest.build();
  }

  private void writeXmlOutput(List<TestResults> allResults, String pathToXmlTestOutput)
      throws IOException {
    Writer writer = null;
    try {
      writer = Files.newWriter(new File(pathToXmlTestOutput), Charsets.UTF_8);
      writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tests>\n");

      for (TestResults results : allResults) {
        for (TestCaseSummary testCase : results.getTestCases()) {
          writer.write(String.format("<test name=\"%s\" status=\"%s\" time=\"%s\" />\n",
              Escaper.escapeAsXmlString(testCase.getTestCaseName()),
              testCase.isSuccess() ? "PASS" : "FAIL",
              testCase.getTotalTime()));
        }
      }

      writer.write("</tests>\n");
    } finally {
      Closeables.close(writer, false /* swallowIOException */);
    }
  }

  @Override
  String getUsageIntro() {
    return "Specify build rules to test.";
  }

}
