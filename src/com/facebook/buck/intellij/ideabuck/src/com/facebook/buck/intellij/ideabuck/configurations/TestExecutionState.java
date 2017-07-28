/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.configurations;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckCommandHandler;
import com.facebook.buck.intellij.ideabuck.config.BuckModule;
import com.facebook.buck.intellij.ideabuck.debugger.AttachDebuggerUtil;
import com.facebook.buck.intellij.ideabuck.ui.BuckToolWindowFactory;
import com.intellij.execution.DefaultExecutionResult;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class TestExecutionState implements RunProfileState {
  protected static final Logger LOG = Logger.getInstance(BuckCommandHandler.class);

  public static final Pattern DEBUG_SUSPEND_PATTERN =
      Pattern.compile(
          "Debugging. Suspending JVM. Connect a JDWP debugger to port (\\d+) to proceed.");
  final TestConfiguration mConfiguration;
  final Project mProject;

  public TestExecutionState(TestConfiguration mConfiguration, Project project) {
    this.mConfiguration = mConfiguration;
    this.mProject = project;
  }

  @Nullable
  @Override
  public ExecutionResult execute(Executor executor, @NotNull ProgramRunner runner)
      throws ExecutionException {
    final ProcessHandler processHandler = runBuildCommand(executor);
    final TestConsoleProperties properties =
        new BuckTestConsoleProperties(
            processHandler, mProject, mConfiguration, "Buck test", executor);
    final ConsoleView console =
        SMTestRunnerConnectionUtil.createAndAttachConsole("buck test", processHandler, properties);
    return new DefaultExecutionResult(console, processHandler, AnAction.EMPTY_ARRAY);
  }

  private ProcessHandler runBuildCommand(Executor executor) {
    final BuckModule buckModule = mProject.getComponent(BuckModule.class);
    final String target = mConfiguration.data.target;
    final String additionalParams = mConfiguration.data.additionalParams;
    final String testSelectors = mConfiguration.data.testSelectors;
    final String title = "Buck Test " + target;

    buckModule.attach(target);

    final BuckBuildCommandHandler handler =
        new BuckBuildCommandHandler(
            mProject, mProject.getBaseDir(), BuckCommand.TEST, /* doStartNotify */ false) {
          @Override
          protected void notifyLines(Key outputType, Iterable<String> lines) {
            super.notifyLines(outputType, lines);
            if (outputType != ProcessOutputTypes.STDERR) {
              return;
            }
            for (String line : lines) {
              final Matcher matcher = DEBUG_SUSPEND_PATTERN.matcher(line);
              if (matcher.find()) {
                final String port = matcher.group(1);
                AttachDebuggerUtil.attachDebugger(title, port, mProject);
              }
            }
          }
        };
    if (!target.isEmpty()) {
      handler.command().addParameter(target);
    }
    if (!testSelectors.isEmpty()) {
      handler.command().addParameter("--test-selectors");
      handler.command().addParameter(testSelectors);
    }
    if (!additionalParams.isEmpty()) {
      for (String param : additionalParams.split("\\s")) {
        handler.command().addParameter(param);
      }
    }
    if (executor.getId().equals(DefaultDebugExecutor.EXECUTOR_ID)) {
      handler.command().addParameter("--debug");
    }
    handler.start();
    final OSProcessHandler result = handler.getHandler();
    BuckToolWindowFactory.showRunToolWindowAfterOSProcessHandler(result, title, mProject);
    return result;
  }
}
