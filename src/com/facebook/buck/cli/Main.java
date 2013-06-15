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

import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

public final class Main {

  private static final String DEFAULT_BUCK_CONFIG_FILE_NAME = ".buckconfig";
  private static final String DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME = ".buckconfig.local";

  private final PrintStream stdOut;
  private final PrintStream stdErr;

  public Main(PrintStream stdOut, PrintStream stdErr) {
    this.stdOut = Preconditions.checkNotNull(stdOut);
    this.stdErr = Preconditions.checkNotNull(stdErr);
  }

  /** Prints the usage message to standard error. */
  @VisibleForTesting
  int usage() {
    stdErr.println("buck build tool");

    stdErr.println("usage:");
    stdErr.println("  buck [options]");
    stdErr.println("  buck command --help");
    stdErr.println("  buck command [command-options]");
    stdErr.println("available commands:");

    int lengthOfLongestCommand = 0;
    for (Command command : Command.values()) {
      String name = command.name();
      if (name.length() > lengthOfLongestCommand) {
        lengthOfLongestCommand = name.length();
      }
    }

    for (Command command : Command.values()) {
      String name = command.name().toLowerCase();
      stdErr.printf("  %s%s  %s\n",
          name,
          Strings.repeat(" ", lengthOfLongestCommand - name.length()),
          command.getShortDescription());
    }

    stdErr.println("options:");
    new GenericBuckOptions(stdOut, stdErr).printUsage();
    return 1;
  }

  /**
   * @param args command line arguments
   * @return an exit code or {@code null} if this is a process that should not exit
   */
  public int runMainWithExitCode(File projectRoot, String... args) throws IOException {
    if (args.length == 0) {
      return usage();
    }

    BuckConfig buckConfig = createBuckConfig(projectRoot);
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectRoot);
    Optional<Command> command = Command.getCommandForName(args[0]);
    if (command.isPresent()) {
      String[] remainingArgs = new String[args.length - 1];
      System.arraycopy(args, 1, remainingArgs, 0, remainingArgs.length);
      Console console = new Console(stdOut, stdErr, buckConfig.getAnsi());
      CommandRunnerParams params = new CommandRunnerParams(
          console,
          buckConfig.getArtifactCache(),
          projectFilesystem,
          new KnownBuildRuleTypes());
      return command.get().execute(remainingArgs, buckConfig, params);
    } else {
      int exitCode = new GenericBuckOptions(stdOut, stdErr).execute(args);
      if (exitCode == GenericBuckOptions.SHOW_MAIN_HELP_SCREEN_EXIT_CODE) {
        return usage();
      } else {
        return exitCode;
      }
    }
  }

  /**
   * @param projectRoot The directory that is the root of the project being built.
   */
  private static BuckConfig createBuckConfig(File projectRoot) throws IOException {
    ImmutableList.Builder<File> configFileBuilder = ImmutableList.builder();
    File configFile = new File(projectRoot, DEFAULT_BUCK_CONFIG_FILE_NAME);
    if (configFile.isFile()) {
      configFileBuilder.add(configFile);
    }
    File overrideConfigFile = new File(projectRoot, DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME);
    if (overrideConfigFile.isFile()) {
      configFileBuilder.add(overrideConfigFile);
    }

    ImmutableList<File> configFiles = configFileBuilder.build();
    return BuckConfig.createFromFiles(projectRoot, configFiles);
  }

  private int tryRunMainWithExitCode(File projectRoot, String... args) throws IOException {
    try {
      return runMainWithExitCode(projectRoot, args);
    } catch (HumanReadableException e) {
      Console console = new Console(stdOut, stdErr, new Ansi());
      console.printFailure(e.getHumanReadableErrorMessage());
      return 1;
    }
  }

  public static void main(String[] args) throws IOException {
    Main main = new Main(System.out, System.err);
    File projectRoot = new File(".");
    int exitCode = main.tryRunMainWithExitCode(projectRoot, args);
    System.exit(exitCode);
  }
}
