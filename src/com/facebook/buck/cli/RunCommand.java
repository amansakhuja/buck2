/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public final class RunCommand extends AbstractCommand {

  /**
   * Expected usage:
   *
   * <pre>
   *   buck run //java/com/facebook/tools/munge:munge --mungearg /tmp/input
   * </pre>
   */
  @Argument private List<String> noDashArguments = new ArrayList<>();

  @Nullable
  @Option(
    name = "--command-args-file",
    usage =
        "Serialize the command, args, and environment for running the target to this file, for consumption by the python wrapper.",
    hidden = true
  )
  private String commandArgsFile;

  @Option(name = "--", handler = ConsumeAllOptionsHandler.class)
  private List<String> withDashArguments = new ArrayList<>();

  private final Supplier<ImmutableList<String>> arguments =
      Suppliers.memoize(
          () -> {
            ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
            builder.addAll(noDashArguments);
            builder.addAll(withDashArguments);
            return builder.build();
          });

  @VisibleForTesting
  ImmutableList<String> getArguments() {
    return arguments.get();
  }

  /** @return the arguments (if any) to be passed to the target command. */
  private ImmutableList<String> getTargetArguments() {
    return arguments.get().subList(1, arguments.get().size());
  }

  private boolean hasTargetSpecified() {
    return arguments.get().size() > 0;
  }

  /** @return the normalized target name for command to run. */
  private String getTarget(BuckConfig buckConfig) {
    return Iterables.getOnlyElement(
        getCommandLineBuildTargetNormalizer(buckConfig).normalize(arguments.get().get(0)));
  }

  @Override
  public String getShortDescription() {
    return "runs a target as a command";
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    if (!hasTargetSpecified()) {
      params.getBuckEventBus().post(ConsoleEvent.severe("No target given to run"));
      params.getBuckEventBus().post(ConsoleEvent.severe("buck run <target> <arg1> <arg2>..."));
      return 1;
    }

    // Make sure the target is built.
    BuildCommand buildCommand =
        new BuildCommand(ImmutableList.of(getTarget(params.getBuckConfig())));
    try (Closeable contextCloser = buildCommand.prepareExecutionContext(params)) {
      int exitCode = buildCommand.runWithoutHelp(params);
      if (exitCode != 0) {
        return exitCode;
      }
    }

    String targetName = getTarget(params.getBuckConfig());
    BuildTarget target =
        Iterables.getOnlyElement(
            getBuildTargets(params.getCell().getCellPathResolver(), ImmutableSet.of(targetName)));

    Build build = buildCommand.getBuild();
    BuildRule targetRule;
    targetRule = build.getRuleResolver().requireRule(target);
    BinaryBuildRule binaryBuildRule = null;
    if (targetRule instanceof BinaryBuildRule) {
      binaryBuildRule = (BinaryBuildRule) targetRule;
    }
    if (binaryBuildRule == null) {
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.severe(
                  "target "
                      + targetName
                      + " is not a binary rule (only binary rules can be `run`)"));
      return 1;
    }

    // If we're running with buckd, we want to disconnect from NailGun and run the rule in the
    // user's shell.  Otherwise, we end up holding the command semaphore while running the program,
    // which blocks concurrent builds (and can mess up handling of Ctrl-C).
    //
    // We support this behavior by writing {path, args, env} to a file passed in from the python
    // wrapper and returning immediately.  The wrapper then deserializes this file and exec's the
    // command.
    //
    // If we haven't received a command args file, we assume it's fine to just run in-process.
    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(build.getRuleResolver()));
    Tool executable = binaryBuildRule.getExecutableCommand();
    if (commandArgsFile == null) {
      ListeningProcessExecutor processExecutor = new ListeningProcessExecutor();
      ProcessExecutorParams processExecutorParams =
          ProcessExecutorParams.builder()
              .addAllCommand(executable.getCommandPrefix(resolver))
              .addAllCommand(getTargetArguments())
              .setEnvironment(
                  ImmutableMap.<String, String>builder()
                      .putAll(params.getEnvironment())
                      .putAll(executable.getEnvironment(resolver))
                      .build())
              .setDirectory(params.getCell().getFilesystem().getRootPath())
              .build();
      ForwardingProcessListener processListener =
          new ForwardingProcessListener(
              params.getConsole().getStdOut(), params.getConsole().getStdErr());
      ListeningProcessExecutor.LaunchedProcess process =
          processExecutor.launchProcess(processExecutorParams, processListener);
      try {
        return processExecutor.waitForProcess(process);
      } finally {
        processExecutor.destroyProcess(process, /* force */ false);
        processExecutor.waitForProcess(process);
      }
    } else {
      ImmutableList<String> argv =
          ImmutableList.<String>builder()
              .addAll(executable.getCommandPrefix(resolver))
              .addAll(getTargetArguments())
              .build();
      ImmutableMap<String, Object> cmd =
          ImmutableMap.of(
              "path", argv.get(0),
              "argv", argv,
              "envp",
                  ImmutableMap.<String, String>builder()
                      .putAll(params.getEnvironment())
                      .putAll(executable.getEnvironment(resolver))
                      .build(),
              "cwd", params.getCell().getFilesystem().getRootPath());
      Files.write(Paths.get(commandArgsFile), ObjectMappers.WRITER.writeValueAsBytes(cmd));
      return 0;
    }
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }
}
