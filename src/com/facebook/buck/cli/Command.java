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

import com.facebook.buck.util.Console;
import com.facebook.buck.util.MoreStrings;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;

public enum Command {

  AUDIT(
      "lists the inputs for the specified target",
      AuditCommandRunner.class),
  BUILD(
      "builds the specified target",
      BuildCommand.class),
  CACHE(
      "makes calls to the artifact cache",
      CacheCommand.class),
  CLEAN(
      "deletes any generated files",
      CleanCommand.class),
  INSTALL(
      "builds and installs an APK",
      InstallCommand.class),
  PROJECT(
      "generates project configuration files for an IDE",
      ProjectCommand.class),
  QUERY(
      "queries the dependency graph",
      QueryCommand.class),
  QUICKSTART(
      "generates a default project directory",
      QuickstartCommand.class),
  TARGETS(
      "prints the list of buildable targets",
      TargetsCommand.class),
  TEST(
      "builds and runs the tests for the specified target",
      TestCommand.class),
  UNINSTALL(
      "uninstalls an APK",
      UninstallCommand.class),
  ;

  /**
   * Defines the maximum possible fuzziness of a input command. If the
   * levenshtein distance between the fuzzy input and the closest command is
   * larger than MAX_ERROR_RATIO * length_of_closest_command, rejects the input.
   * The value is chosen empirically so that minor typos can be corrected.
   */
  public static final double MAX_ERROR_RATIO = 0.5;

  private final String shortDescription;
  private final Class<? extends CommandRunner> commandRunnerClass;

  private Command(
      String shortDescription,
      Class<? extends CommandRunner> commandRunnerClass) {
    this.shortDescription = shortDescription;
    this.commandRunnerClass = commandRunnerClass;
  }

  public String getShortDescription() {
    return shortDescription;
  }

  public int execute(String[] args,
      BuckConfig buckConfig,
      CommandRunnerParams params) throws IOException {
      CommandRunner commandRunner;
      try {
        commandRunner = commandRunnerClass
            .getDeclaredConstructor(CommandRunnerParams.class)
            .newInstance(params);
      } catch (InstantiationException
          | IllegalAccessException
          | IllegalArgumentException
          | InvocationTargetException
          | NoSuchMethodException
          | SecurityException e) {
        throw Throwables.propagate(e);
      }
      return commandRunner.runCommand(buckConfig, args);
  }

  /**
   * @return a non-empty {@link Optional} if {@code name} corresponds to a
   *     command or its levenshtein distance to the closest command isn't larger
   *     than {@link #MAX_ERROR_RATIO} * length_of_closest_command; otherwise, an
   *     empty {@link Optional}. This will return the latter if the user tries
   *     to run something like {@code buck --help}.
   */
  public static Optional<Command> getCommandForName(String name, Console console) {
    Preconditions.checkNotNull(name);
    Preconditions.checkNotNull(console);

    Command command;
    try {
      command = valueOf(name.toUpperCase());
    } catch (IllegalArgumentException e) {
      Optional<Command> fuzzyCommand = fuzzyMatch(name.toUpperCase());

      if (fuzzyCommand.isPresent()) {
        PrintStream stdErr = console.getStdErr();
        stdErr.printf("(Cannot find command '%s', assuming command '%s'.)\n",
            name,
            fuzzyCommand.get().name().toLowerCase());
      }

      return fuzzyCommand;
    }

    return Optional.of(command);
  }

  private static Optional<Command> fuzzyMatch(String name) {
    Preconditions.checkNotNull(name);
    name = name.toUpperCase();

    int minDist = Integer.MAX_VALUE;
    Command closestCommand = null;

    for (Command command : values()) {
      int levenshteinDist = MoreStrings.getLevenshteinDistance(name, command.name());
      if (levenshteinDist < minDist) {
        minDist = levenshteinDist;
        closestCommand = command;
      }
    }

    if (closestCommand != null &&
        ((double)minDist) / closestCommand.name().length() <= MAX_ERROR_RATIO) {
      return Optional.of(closestCommand);
    }

    return Optional.absent();
  }
}
