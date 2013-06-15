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

import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.InputRule;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Comparator;
import java.util.Set;

/**
 * Outputs targets that own a specified list of files.
 */
public class AuditOwnerCommand extends AbstractCommandRunner<AuditOwnerOptions> {

  private static final String FILE_INDENT = "    ";

  public AuditOwnerCommand(CommandRunnerParams params) {
    super(params);
  }

  @VisibleForTesting
  static final class OwnersReport {
    final ImmutableSetMultimap<BuildRule, InputRule> owners;
    final ImmutableSet<InputRule> inputsWithNoOwners;
    final ImmutableSet<String> nonExistentInputs;
    final ImmutableSet<String> nonFileInputs;

    public OwnersReport(SetMultimap<BuildRule, InputRule> owners,
                        Set<InputRule> inputsWithNoOwners,
                        Set<String> nonExistentInputs,
                        Set<String> nonFileInputs) {
      this.owners = ImmutableSetMultimap.copyOf(owners);
      this.inputsWithNoOwners = ImmutableSet.copyOf(inputsWithNoOwners);
      this.nonExistentInputs = ImmutableSet.copyOf(nonExistentInputs);
      this.nonFileInputs = ImmutableSet.copyOf(nonFileInputs);
    }
  }

  @VisibleForTesting
  AuditOwnerCommand(Console console,
                    ProjectFilesystem projectFilesystem,
                    KnownBuildRuleTypes buildRuleTypes,
                    ArtifactCache artifactCache) {
    super(console, projectFilesystem, buildRuleTypes, artifactCache);
  }

  @Override
  AuditOwnerOptions createOptions(BuckConfig buckConfig) {
    return new AuditOwnerOptions(buckConfig);
  }

  @Override
  int runCommandWithOptions(AuditOwnerOptions options) throws IOException {

    // Build full graph.
    PartialGraph graph;
    try {
      graph = PartialGraph.createFullGraph(
          getProjectFilesystem().getProjectRoot(),
          getArtifactCache(),
          options.getDefaultIncludes());
    } catch (NoSuchBuildTargetException e) {
      console.printFailureWithoutStacktrace(e);
      return 1;
    }

    OwnersReport report = generateOwnersReport(graph.getDependencyGraph(), options);
    printReport(options, report);
    return 0;
  }

  @VisibleForTesting
  OwnersReport generateOwnersReport(DependencyGraph graph, AuditOwnerOptions options) {

    // Process arguments assuming they are all relative file paths
    Set<InputRule> inputs = Sets.newHashSet();
    Set<String> nonExistentInputs = Sets.newHashSet();
    Set<String> nonFileInputs = Sets.newHashSet();

    for (String filePath : options.getArguments()) {
      File file = getProjectFilesystem().getFileForRelativePath(filePath);
      if (!file.exists()) {
        nonExistentInputs.add(filePath);
      } else if (!file.isFile()) {
        nonFileInputs.add(filePath);
      } else {
        inputs.add(new InputRule(filePath));
      }
    }

    // Try to find owners for each valid and existing file
    Set<InputRule> inputsWithNoOwners = Sets.newHashSet(inputs);
    SetMultimap<BuildRule, InputRule> owners = createOwnersMap();
    for (BuildRule rule : graph.getNodes()) {
      for (InputRule ruleInput : rule.getInputs()) {
        if (inputs.contains(ruleInput)) {
          inputsWithNoOwners.remove(ruleInput);
          owners.put(rule, ruleInput);
        }
      }
    }

    // Try to guess owners for nonexistent files
    if (options.isGuessForDeletedEnabled()) {
      guessOwnersForNonExistentFiles(graph, owners, nonExistentInputs);
    }

    return new OwnersReport(owners, inputsWithNoOwners, nonExistentInputs, nonFileInputs);
  }

  /**
   * Guess target owners for deleted/missing files by finding first
   * BUCK file and assuming that all targets in this file used
   * missing file as input.
   */
  private void guessOwnersForNonExistentFiles(DependencyGraph graph,
      SetMultimap<BuildRule, InputRule> owners, Set<String> nonExistentFiles) {

    for (String nonExistentFile : nonExistentFiles) {
      File file = getProjectFilesystem().getFileForRelativePath(nonExistentFile);
      File buck = findBuckFileFor(file);
      for (BuildRule rule : graph.getNodes()) {
        if (rule.getType() == BuildRuleType.PROJECT_CONFIG) {
          continue;
        }
        File ruleBuck = rule.getBuildTarget().getBuildFile();
        try {
          if (buck.getCanonicalFile().equals(ruleBuck.getCanonicalFile())) {
            owners.put(rule, new InputRule(nonExistentFile));
          }
        } catch (IOException ex) {
          throw Throwables.propagate(ex);
        }
      }
    }
  }

  private File findBuckFileFor(File file) {
    File dir = file;
    if (!dir.isDirectory()) {
      dir = dir.getParentFile();
    }

    File projectRoot = getProjectFilesystem().getProjectRoot();
    while (dir != null && !dir.equals(projectRoot)) {
      File buck = new File(dir, BuckConstant.BUILD_RULES_FILE_NAME);
      if (buck.exists()) {
        return buck;
      }
      dir = dir.getParentFile();
    }
    throw new RuntimeException("Failed to find BUCK file for " + file.getPath());
  }

  private void printReport(AuditOwnerOptions options, OwnersReport report) {

    if (options.isFullReportEnabled()) {
      printFullReport(report);
    } else {
      printOwnersOnlyReport(report);
    }
  }

  /**
   * Print only targets which were identified as owners.
   */
  private void printOwnersOnlyReport(OwnersReport report) {
    Set<BuildRule> sortedRules = report.owners.keySet();
    for (BuildRule rule : sortedRules) {
      console.getStdOut().println(rule.getFullyQualifiedName());
    }
  }

  /**
   * Print detailed report on all owners.
   */
  private void printFullReport(OwnersReport report) {
    PrintStream out = console.getStdOut();
    Ansi ansi = console.getAnsi();
    if (report.owners.isEmpty()) {
      out.println(ansi.asErrorText("No owners found"));
    } else {
      out.println(ansi.asSuccessText("Owners:"));
      for (BuildRule rule : report.owners.keySet()) {
        out.println(rule.getFullyQualifiedName());
        Set<InputRule> files = report.owners.get(rule);
        for (InputRule input : files) {
          out.println(FILE_INDENT + input);
        }
      }
    }

    if (!report.inputsWithNoOwners.isEmpty()) {
      out.println();
      out.println(ansi.asErrorText("Files without owners:"));
      for (InputRule input : report.inputsWithNoOwners) {
        out.println(FILE_INDENT + input);
      }
    }

    if (!report.nonExistentInputs.isEmpty()) {
      out.println();
      out.println(ansi.asErrorText("Non existent files:"));
      for (String input : report.nonExistentInputs) {
        out.println(FILE_INDENT + input);
      }
    }

    if (!report.nonFileInputs.isEmpty()) {
      out.println();
      out.println(ansi.asErrorText("Non-file inputs:"));
      for (String input : report.nonFileInputs) {
        out.println(FILE_INDENT + input);
      }
    }
  }

  private SetMultimap<BuildRule, InputRule> createOwnersMap() {
    Comparator<BuildRule> keyComparator = new Comparator<BuildRule>() {
      @Override
      public int compare(BuildRule o1, BuildRule o2) {
        return o1.getFullyQualifiedName().compareTo(o2.getFullyQualifiedName());
      }
    };

    Comparator<InputRule> valueComparator = new Comparator<InputRule>() {
      @Override
      public int compare(InputRule o1, InputRule o2) {
        return o1.compareTo(o2);
      }
    };

    return TreeMultimap.create(keyComparator, valueComparator);
  }

  @Override
  String getUsageIntro() {
    return "prints targets that own specified files";
  }

}
