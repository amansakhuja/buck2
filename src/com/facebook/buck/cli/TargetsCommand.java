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

import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.json.BuildFileToJsonParser;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.InputRule;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import javax.annotation.Nullable;

public class TargetsCommand extends AbstractCommandRunner<TargetsCommandOptions> {

  public TargetsCommand(CommandRunnerParams params) {
    super(params);
  }

  @VisibleForTesting
  TargetsCommand(PrintStream stdOut,
      PrintStream stdErr,
      Console console,
      ProjectFilesystem projectFilesystem,
      KnownBuildRuleTypes buildRuleTypes,
      ArtifactCache artifactCache) {
    super(stdOut, stdErr, console, projectFilesystem, buildRuleTypes, artifactCache);
  }

  @Override
  TargetsCommandOptions createOptions(BuckConfig buckConfig) {
    return new TargetsCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptions(TargetsCommandOptions options) throws IOException {
    // Exit early if --resolvealias is passed in: no need to parse any build files.
    if (options.isResolveAlias()) {
      return doResolveAlias(options);
    }

    // Verify the --type argument.
    ImmutableSet<String> types = options.getTypes();
    ImmutableSet.Builder<BuildRuleType> buildRuleTypesBuilder = ImmutableSet.builder();
    for (String name : types) {
      try {
        buildRuleTypesBuilder.add(getBuildRuleTypes().getBuildRuleType(name));
      } catch (IllegalArgumentException e) {
        console.printFailure("Invalid build rule type: " + name);
        return 1;
      }
    }

    // Find the build targets that match the specified options.
    Parser parser = createParser();
    ImmutableSet<BuildTarget> matchingBuildTargets;
    try {
      matchingBuildTargets = ImmutableSet.copyOf(
          getBuildTargets(parser, options.getArgumentsFormattedAsBuildTargets()));
    } catch (NoSuchBuildTargetException e) {
      console.printFailureWithoutStacktrace(e);
      return 1;
    }

    // Parse the entire dependency graph.
    PartialGraph graph;
    try {
      graph = PartialGraph.createFullGraph(getProjectFilesystem().getProjectRoot(),
          getArtifactCache(),
          options.getDefaultIncludes());
    } catch (NoSuchBuildTargetException e) {
      console.printFailureWithoutStacktrace(e);
      return 1;
    }

    SortedMap<String, BuildRule> matchingBuildRules = getMatchingBuildRules(
        graph.getDependencyGraph(),
        new TargetsCommandPredicate(
            graph,
            buildRuleTypesBuilder.build(),
            options.getReferencedFiles(getProjectFilesystem().getProjectRoot()),
            matchingBuildTargets));

    // Print out matching targets in alphabetical order.
    if (options.getPrintJson()) {
      printJsonForTargets(matchingBuildRules, options.getDefaultIncludes());
    } else {
      printTargetsList(matchingBuildRules, options.isShowOutput());
    }

    return 0;
  }

  @VisibleForTesting
  void printTargetsList(SortedMap<String, BuildRule> matchingBuildRules,
      boolean showOutput) {
    for (Map.Entry<String, BuildRule> target : matchingBuildRules.entrySet()) {
      String output = target.getKey();
      if (showOutput) {
        File outputFile = target.getValue().getOutput();
        if (outputFile != null) {
          output += " " + outputFile.getPath();
        }
      }
      stdOut.println(output);
    }
  }

  @VisibleForTesting
  SortedMap<String, BuildRule> getMatchingBuildRules(
      final DependencyGraph graph,
      final TargetsCommandPredicate predicate) {
    // Traverse the DependencyGraph and select all of the rules that accepted by Predicate.
    AbstractBottomUpTraversal<BuildRule, SortedMap<String, BuildRule>> traversal =
        new AbstractBottomUpTraversal<BuildRule, SortedMap<String, BuildRule>>(graph) {

      final SortedMap<String, BuildRule> matchingBuildRules = Maps.newTreeMap();

      @Override
      public void visit(BuildRule rule) {
        if (predicate.apply(rule)) {
          matchingBuildRules.put(rule.getFullyQualifiedName(), rule);
        }
      }

      @Override
      public SortedMap<String, BuildRule> getResult() {
        return matchingBuildRules;
      }
    };

    traversal.traverse();
    return traversal.getResult();
  }

  @Override
  String getUsageIntro() {
    return "prints the list of buildable targets";
  }

  @VisibleForTesting
  void printJsonForTargets(SortedMap<String, BuildRule> buildIndex,
      Iterable<String> defaultIncludes) throws IOException {

    // Print the JSON representation of the build rule for the specified target(s).
    stdOut.println("[");

    ObjectMapper mapper = new ObjectMapper();
    Iterator<String> keySetIterator = buildIndex.keySet().iterator();
    while (keySetIterator.hasNext()) {
      String key = keySetIterator.next();
      BuildRule buildRule = buildIndex.get(key);
      BuildTarget buildTarget = buildRule.getBuildTarget();
      File buildFile = buildTarget.getBuildFile();

      List<Map<String, Object>> rules = BuildFileToJsonParser.getAllRules(
          getProjectFilesystem().getProjectRoot().getAbsolutePath(),
          Optional.of(buildFile.getPath()),
          defaultIncludes);

      // Find the build rule information that corresponds to this build buildTarget.
      Map<String, Object> targetRule = null;
      for (Map<String, Object> rule : rules) {
        String name = (String)rule.get("name");
        if (name.equals(buildTarget.getShortName())) {
          targetRule = rule;
          break;
        }
      }

      if (targetRule == null) {
        console.printFailure(
            "unable to find rule for target " + buildTarget.getFullyQualifiedName());
        continue;
      }

      File outputFile = buildRule.getOutput();
      if (outputFile != null) {
        targetRule.put("buck.output_file", outputFile.getPath());
      }

      // Sort the rule items, both so we have a stable order for unit tests and
      // to improve readability of the output.
      SortedMap<String, Object> sortedTargetRule = Maps.newTreeMap();
      sortedTargetRule.putAll(targetRule);

      // Print the build rule information as JSON.
      StringWriter stringWriter = new StringWriter();
      mapper.writerWithDefaultPrettyPrinter().writeValue(stringWriter, sortedTargetRule);
      String output = stringWriter.getBuffer().toString();
      if (keySetIterator.hasNext()) {
        output += ",";
      }
      stdOut.println(output);
    }

    stdOut.println("]");
  }

  /**
   * Assumes each argument passed to this command is an alias defined in .buckconfig,
   * or a fully qualified (non-alias) target to be verified by checking the build files.
   * Prints the build target that each alias maps to on its own line to standard out.
   */
  private int doResolveAlias(TargetsCommandOptions options) throws IOException {
    List<String> resolvedAliases = Lists.newArrayList();
    for (String alias : options.getArguments()) {
      String buildTarget;
      if (alias.startsWith("//")) {
        buildTarget = validateBuildTargetForFullyQualifiedTarget(alias, options);
        if (buildTarget == null) {
          throw new HumanReadableException("%s is not a valid target.", alias);
        }
      } else {
        buildTarget = options.getBuildTargetForAlias(alias);
        if (buildTarget == null) {
          throw new HumanReadableException("%s is not an alias.", alias);
        }
      }
      resolvedAliases.add(buildTarget);
    }

    for (String resolvedAlias : resolvedAliases) {
      stdOut.println(resolvedAlias);
    }

    return 0;
  }

  /**
   * Verify that the given target is a valid full-qualified (non-alias) target.
   */
  @Nullable
  @VisibleForTesting
  String validateBuildTargetForFullyQualifiedTarget(
      String target, TargetsCommandOptions options) throws IOException {
    BuildTarget buildTarget;
    try {
      buildTarget = options.getBuildTargetForFullyQualifiedTarget(target);
    } catch (NoSuchBuildTargetException e) {
      return null;
    }

    // Get all valid targets in our target directory by reading the build file.
    List<Map<String, Object>> ruleObjects = BuildFileToJsonParser.getAllRules(
        getProjectFilesystem().getProjectRoot().getAbsolutePath(),
        Optional.of(buildTarget.getBuildFile().toString()),
        options.getDefaultIncludes());
    // Check that the given target is a valid target.
    for (Map<String,Object> rule : ruleObjects) {
      String name = (String)rule.get("name");
      if (name.equals(buildTarget.getShortName())) {
        return buildTarget.getFullyQualifiedName();
      }
    }
    return null;
  }

  static class TargetsCommandPredicate implements Predicate<BuildRule> {

    private DependencyGraph graph;
    private ImmutableSet<BuildRuleType> buildRuleTypes;
    private ImmutableSet<InputRule> referencedInputs;
    private Set<String> basePathOfTargets;
    private Set<BuildRule> dependentTargets;
    private Set<BuildTarget> matchingBuildRules;

    public TargetsCommandPredicate(
        PartialGraph partialGraph,
        ImmutableSet<BuildRuleType> buildRuleTypes,
        ImmutableSet<String> referencedFiles,
        ImmutableSet<BuildTarget> matchingBuildRules) {
      this.graph = partialGraph.getDependencyGraph();
      this.buildRuleTypes = Preconditions.checkNotNull(buildRuleTypes);
      this.matchingBuildRules = Preconditions.checkNotNull(matchingBuildRules);

      Preconditions.checkNotNull(referencedFiles);
      if (!referencedFiles.isEmpty()) {
        this.referencedInputs = InputRule.inputPathsAsInputRules(
            ImmutableSortedSet.copyOf(referencedFiles));
        BuildFileTree tree = new BuildFileTree(partialGraph.getTargets());
        basePathOfTargets = Sets.newHashSet();
        dependentTargets = Sets.newHashSet();
        for (InputRule input : referencedInputs) {
          basePathOfTargets.add(tree.getBasePathOfAncestorTarget(
              input.getBuildTarget().getBasePath()));
        }
      } else {
        basePathOfTargets = ImmutableSet.of();
        dependentTargets = ImmutableSet.of();
      }
    }

    @Override
    public boolean apply(BuildRule rule) {
      boolean isDependent = true;
      if (referencedInputs != null) {
        // Indirectly depend on some referenced file.
        isDependent = !Collections.disjoint(graph.getOutgoingNodesFor(rule), dependentTargets);

        // Any referenced file, only those with the nearest BuildTarget can
        // directly depend on that file.
        if (!isDependent && basePathOfTargets.contains(rule.getBuildTarget().getBasePath())) {
          for (InputRule input : rule.getInputs()) {
            if (referencedInputs.contains(input)) {
              isDependent = true;
              break;
            }
          }
        }

        if (isDependent) {
          // Save the rule only when exists referenced file
          // and this rule depend on at least one referenced file.
          dependentTargets.add(rule);
        }
      }

      if (!matchingBuildRules.isEmpty() &&
          !matchingBuildRules.contains(rule.getBuildTarget())) {
        return false;
      }

      return (isDependent && (buildRuleTypes.isEmpty() || buildRuleTypes.contains(rule.getType())));
    }

  }
}
