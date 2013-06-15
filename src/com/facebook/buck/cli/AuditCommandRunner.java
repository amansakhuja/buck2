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

import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.util.Map;

public class AuditCommandRunner implements CommandRunner {
  private static ImmutableMap<String, ? extends AbstractCommandRunner<?>> auditCommands;

  private final Console console;

  public AuditCommandRunner(CommandRunnerParams params) {
    console = new Console(params.getStdout(), params.getStderr(), params.getAnsi());
    setAuditCommands(params);
  }

  @Override
  public int runCommand(BuckConfig buckConfig, String[] args) throws IOException {
    if (args.length == 0) {
      console.printFailure("No audit command is given.");
      printUsage();
      return 1;
    }

    String auditCmd = args[0];
    if (getAuditCommands().containsKey(auditCmd)) {
      CommandRunner cmd = getAuditCommands().get(auditCmd);
      String[] newArgs = new String[args.length - 1];
      System.arraycopy(args, 1, newArgs, 0, newArgs.length);
      return cmd.runCommand(buckConfig, newArgs);
    } else {
      console.printFailure("Unknown audit command: " + auditCmd);
      printUsage();
      return 1;
    }
  }

  private ImmutableMap<String, ? extends AbstractCommandRunner<?>> getAuditCommands() {
    return auditCommands;
  }

  private void setAuditCommands(CommandRunnerParams params) {
    auditCommands = ImmutableMap.of(
        "input",     new AuditInputCommand(params),
        "classpath", new AuditClasspathCommand(params),
        "owner",     new AuditOwnerCommand(params));
  }

  private void printUsage() {
    // TODO: implement better way of showing help
    console.getStdOut().println("buck audit <cmd>");
    for (Map.Entry<String, ? extends AbstractCommandRunner<?>> entry :
        getAuditCommands().entrySet()) {
      console.getStdOut().println("  " + entry.getKey() + " - " + entry.getValue().getUsageIntro());
    }
  }
}
