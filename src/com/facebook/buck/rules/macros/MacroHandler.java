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

package com.facebook.buck.rules.macros;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.model.MacroFinder;
import com.facebook.buck.model.MacroMatchResult;
import com.facebook.buck.model.MacroReplacer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;

/** Extracts macros from input strings and calls registered expanders to handle their input. */
public class MacroHandler {

  private static final MacroFinder MACRO_FINDER = new MacroFinder();

  private final ImmutableMap<String, MacroExpander> expanders;

  public MacroHandler(ImmutableMap<String, MacroExpander> expanders) {
    this.expanders = addOutputToFileExpanders(expanders);
  }

  public Function<String, String> getExpander(
      final BuildTarget target,
      final CellPathResolver cellNames,
      final BuildRuleResolver resolver) {
    return blob -> {
      try {
        return expand(target, cellNames, resolver, blob);
      } catch (MacroException e) {
        throw new HumanReadableException("%s: %s", target, e.getMessage());
      }
    };
  }

  private static ImmutableMap<String, MacroExpander> addOutputToFileExpanders(
      ImmutableMap<String, MacroExpander> source) {
    ImmutableMap.Builder<String, MacroExpander> builder = ImmutableMap.builder();
    for (Map.Entry<String, MacroExpander> entry : source.entrySet()) {
      builder.put(entry.getKey(), entry.getValue());
      builder.put("@" + entry.getKey(), entry.getValue());
    }
    return builder.build();
  }

  public MacroExpander getExpander(String name) throws MacroException {
    MacroExpander expander = expanders.get(name);
    if (expander == null) {
      throw new MacroException(String.format("no such macro \"%s\"", name));
    }
    return expander;
  }

  public String expand(
      final BuildTarget target,
      final CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      String blob)
      throws MacroException {
    return expand(target, cellNames, resolver, blob, new HashMap<>());
  }

  public String expand(
      final BuildTarget target,
      final CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      String blob,
      Map<MacroMatchResult, Object> precomputedWorkCache)
      throws MacroException {
    ImmutableMap<String, MacroReplacer> replacers =
        getMacroReplacers(target, cellNames, resolver, precomputedWorkCache);
    return MACRO_FINDER.replace(replacers, blob, true);
  }

  public ImmutableMap<String, MacroReplacer> getMacroReplacers(
      final BuildTarget target,
      final CellPathResolver cellNames,
      final BuildRuleResolver resolver) {
    return getMacroReplacers(target, cellNames, resolver, new HashMap<>());
  }

  public ImmutableMap<String, MacroReplacer> getMacroReplacers(
      final BuildTarget target,
      final CellPathResolver cellNames,
      final BuildRuleResolver resolver,
      Map<MacroMatchResult, Object> precomputedWorkCache) {
    ImmutableMap.Builder<String, MacroReplacer> replacers = ImmutableMap.builder();
    for (final Map.Entry<String, MacroExpander> entry : expanders.entrySet()) {
      MacroReplacer replacer;
      final boolean shouldOutputToFile = entry.getKey().startsWith("@");
      try {
        final MacroExpander expander = getExpander(entry.getKey());
        replacer =
            input -> {
              Object precomputedWork =
                  ensurePrecomputedWork(
                      input, expander, precomputedWorkCache, target, cellNames, resolver);
              if (shouldOutputToFile) {
                return expander.expandForFile(
                    target, cellNames, resolver, input.getMacroInput(), precomputedWork);
              } else {
                return expander.expand(
                    target, cellNames, resolver, input.getMacroInput(), precomputedWork);
              }
            };
      } catch (MacroException e) {
        throw new RuntimeException("No matching macro handler found", e);
      }
      if (entry.getKey().startsWith("@")) {
        replacer = OutputToFileExpanderUtils.wrapReplacerWithFileOutput(replacer, target, resolver);
      }
      replacers.put(entry.getKey(), replacer);
    }
    return replacers.build();
  }

  public ImmutableList<BuildRule> extractBuildTimeDeps(
      BuildTarget target, CellPathResolver cellNames, BuildRuleResolver resolver, String blob)
      throws MacroException {
    return extractBuildTimeDeps(target, cellNames, resolver, blob, new HashMap<>());
  }

  public ImmutableList<BuildRule> extractBuildTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      String blob,
      Map<MacroMatchResult, Object> precomputedWorkCache)
      throws MacroException {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();

    // Iterate over all macros found in the string, collecting all `BuildTargets` each expander
    // extract for their respective macros.
    for (MacroMatchResult matchResult : getMacroMatchResults(blob)) {
      MacroExpander expander = getExpander(matchResult.getMacroType());
      Object precomputedWork =
          ensurePrecomputedWork(
              matchResult, expander, precomputedWorkCache, target, cellNames, resolver);
      ImmutableList<BuildRule> buildTimeDeps =
          expander.extractBuildTimeDeps(
              target, cellNames, resolver, matchResult.getMacroInput(), precomputedWork);
      deps.addAll(buildTimeDeps);
    }

    return deps.build();
  }

  public void extractParseTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      String blob,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder)
      throws MacroException {

    // Iterate over all macros found in the string, collecting all `BuildTargets` each expander
    // extract for their respective macros.
    for (MacroMatchResult matchResult : getMacroMatchResults(blob)) {
      getExpander(matchResult.getMacroType())
          .extractParseTimeDeps(
              target,
              cellNames,
              matchResult.getMacroInput(),
              buildDepsBuilder,
              targetGraphOnlyDepsBuilder);
    }
  }

  public ImmutableList<Object> extractRuleKeyAppendables(
      BuildTarget target, CellPathResolver cellNames, BuildRuleResolver resolver, String blob)
      throws MacroException {
    return extractRuleKeyAppendables(target, cellNames, resolver, blob, new HashMap<>());
  }

  public ImmutableList<Object> extractRuleKeyAppendables(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      String blob,
      Map<MacroMatchResult, Object> precomputedWorkCache)
      throws MacroException {

    ImmutableList.Builder<Object> targets = ImmutableList.builder();

    // Iterate over all macros found in the string, collecting all `BuildTargets` each expander
    // extract for their respective macros.
    for (MacroMatchResult matchResult : getMacroMatchResults(blob)) {
      MacroExpander expander = getExpander(matchResult.getMacroType());
      Object precomputedWork =
          ensurePrecomputedWork(
              matchResult, expander, precomputedWorkCache, target, cellNames, resolver);
      Object ruleKeyAppendable =
          expander.extractRuleKeyAppendables(
              target, cellNames, resolver, matchResult.getMacroInput(), precomputedWork);
      if (ruleKeyAppendable != null) {
        targets.add(ruleKeyAppendable);
      }
    }

    return targets.build();
  }

  public ImmutableList<MacroMatchResult> getMacroMatchResults(String blob) throws MacroException {
    return MACRO_FINDER.findAll(expanders.keySet(), blob);
  }

  private static Object ensurePrecomputedWork(
      MacroMatchResult matchResult,
      MacroExpander expander,
      Map<MacroMatchResult, Object> precomputedWorkCache,
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver)
      throws MacroException {
    if (!precomputedWorkCache.containsKey(matchResult)) {
      precomputedWorkCache.put(
          matchResult,
          expander.precomputeWork(target, cellNames, resolver, matchResult.getMacroInput()));
    }
    return precomputedWorkCache.get(matchResult);
  }
}
