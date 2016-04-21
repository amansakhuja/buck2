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

package com.facebook.buck.cxx;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public class RanlibStep extends ShellStep {

  private final ProjectFilesystem filesystem;
  private final ImmutableMap<String, String> ranlibEnv;
  private final ImmutableList<String> ranlibPrefix;
  private final Path output;

  public RanlibStep(
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> ranlibEnv,
      ImmutableList<String> ranlibPrefix, Path output) {
    super(filesystem.getRootPath());
    this.filesystem = filesystem;
    this.ranlibEnv = ranlibEnv;
    this.ranlibPrefix = ranlibPrefix;
    this.output = output;
  }

  @Override
  public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
    return ranlibEnv;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    return ImmutableList.<String>builder()
        .addAll(ranlibPrefix)
        .add(filesystem.resolve(output).toString())
        .build();
  }

  @Override
  public String getShortName() {
    return "ranlib";
  }

}
