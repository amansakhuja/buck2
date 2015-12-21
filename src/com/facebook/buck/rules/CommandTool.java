/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A {@link Tool} based on a list of arguments formed by {@link SourcePath}s.
 *
 * Example:
 * <pre>
 * {@code
 *   Tool compiler = new CommandTool.Builder()
 *      .addArg(compilerPath)
 *      .addArg("-I%s", defaultIncludeDir)
 *      .build();
 * }
 * </pre>
 */
public class CommandTool implements Tool {

  private final Optional<Tool> baseTool;
  private final ImmutableList<Arg> args;
  private final ImmutableMap<String, Arg> environment;
  private final ImmutableSortedSet<SourcePath> extraInputs;
  private final ImmutableSortedSet<BuildRule> extraDeps;

  private CommandTool(
      Optional<Tool> baseTool,
      ImmutableList<Arg> args,
      ImmutableMap<String, Arg> environment,
      ImmutableSortedSet<SourcePath> extraInputs,
      ImmutableSortedSet<BuildRule> extraDeps) {
    this.baseTool = baseTool;
    this.args = args;
    this.environment = environment;
    this.extraInputs = extraInputs;
    this.extraDeps = extraDeps;
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    ImmutableSortedSet.Builder<SourcePath> inputs = ImmutableSortedSet.naturalOrder();
    if (baseTool.isPresent()) {
      inputs.addAll(baseTool.get().getInputs());
    }
    for (Arg arg : args) {
      inputs.addAll(arg.getInputs());
    }
    inputs.addAll(extraInputs);
    return inputs.build();
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
    if (baseTool.isPresent()) {
      deps.addAll(baseTool.get().getDeps(resolver));
    }
    deps.addAll(resolver.filterBuildRuleInputs(getInputs()));
    deps.addAll(extraDeps);
    return deps.build();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    if (baseTool.isPresent()) {
      command.addAll(baseTool.get().getCommandPrefix(resolver));
    }
    for (Arg arg : args) {
      command.add(arg.format(resolver));
    }
    return command.build();
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    ImmutableMap.Builder<String, String> env = ImmutableMap.builder();
    if (baseTool.isPresent()) {
      env.putAll(baseTool.get().getEnvironment(resolver));
    }
    for (ImmutableMap.Entry<String, Arg> var : environment.entrySet()) {
      env.put(var.getKey(), var.getValue().format(resolver));
    }
    return env.build();
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder
        .setReflectively("baseTool", baseTool)
        .setReflectively("args", args)
        .setReflectively("extraInputs", extraInputs);
  }

  // Builder for a `CommandTool`.
  public static class Builder {

    private final Optional<Tool> baseTool;
    private final ImmutableList.Builder<Arg> args = ImmutableList.builder();
    private final ImmutableMap.Builder<String, Arg> environment = ImmutableMap.builder();
    private final ImmutableSortedSet.Builder<SourcePath> extraInputs =
        ImmutableSortedSet.naturalOrder();
    private final ImmutableSortedSet.Builder<BuildRule> extraDeps =
        ImmutableSortedSet.naturalOrder();

    public Builder(Optional<Tool> baseTool) {
      this.baseTool = baseTool;
    }

    public Builder(Tool baseTool) {
      this(Optional.of(baseTool));
    }

    public Builder() {
      this(Optional.<Tool>absent());
    }

    /**
     * Add a {@link String} argument represented by the format string to the command.  The
     * {@code inputs} will be resolved and used to format the format string when this argument is
     * added to the command.
     *
     * @param format the format string representing the argument.
     * @param inputs {@link SourcePath}s to use when formatting {@code format}.
     */
    public Builder addArg(String format, SourcePath... inputs) {
      args.add(new Arg(format, ImmutableList.copyOf(inputs)));
      return this;
    }

    /**
     * Add a `SourcePath` as an argument to the command.
     */
    public Builder addArg(SourcePath input) {
      return addArg("%s", input);
    }

    /**
     * Adds an environment variable key=value.
     */
    public Builder addEnvironment(String key, String format, SourcePath... inputs) {
      environment.put(key, new Arg(format, ImmutableList.copyOf(inputs)));
      return this;
    }

    /**
     * Add a `SourcePath` as an environment variable value.
     */
    public Builder addEnvironment(String key, SourcePath value) {
      return addEnvironment(key, "%s", value);
    }

    /**
     * Adds additional non-argument inputs to the tool.
     */
    public Builder addInputs(Iterable<? extends SourcePath> inputs) {
      extraInputs.addAll(inputs);
      return this;
    }

    public Builder addInput(SourcePath... inputs) {
      return addInputs(ImmutableList.copyOf(inputs));
    }

    /**
     * Adds additional non-argument deps to the tool.
     */
    public Builder addDeps(Iterable<? extends BuildRule> deps) {
      extraDeps.addAll(deps);
      return this;
    }

    public Builder addDep(BuildRule... deps) {
      return addDeps(ImmutableList.copyOf(deps));
    }

    public CommandTool build() {
      return new CommandTool(
          baseTool,
          args.build(),
          environment.build(),
          extraInputs.build(),
          extraDeps.build());
    }

  }

  // Represents a single "argument" in the command list.
  private static class Arg implements RuleKeyAppendable {

    private final String format;
    private final ImmutableList<SourcePath> inputs;

    public Arg(String format, ImmutableList<SourcePath> inputs) {
      this.format = format;
      this.inputs = inputs;
    }

    public String format(SourcePathResolver resolver) {
      return String.format(
          format,
          (Object[]) FluentIterable.from(ImmutableList.copyOf(inputs))
              .transform(resolver.getAbsolutePathFunction())
              .toArray(Path.class));
    }

    public ImmutableList<SourcePath> getInputs() {
      return inputs;
    }

    @Override
    public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
      return builder
          .setReflectively("format", format)
          .setReflectively("inputs", inputs);
    }

  }

}
