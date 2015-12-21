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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;

/**
 * Implementation of {@link BuckConfig} with no data, or only the data specified by
 * {@link FakeBuckConfig.Builder#setSections(ImmutableMap)}}. This makes it possible to get an
 * instance of a {@link BuckConfig} without reading {@code .buckconfig} files from disk. Designed
 * exclusively for testing.
 */
public class FakeBuckConfig {

  private static final ImmutableMap<String, ImmutableMap<String, String>> EMPTY_SECTIONS =
      ImmutableMap.of();

  private FakeBuckConfig() {
    // Utility class
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private ProjectFilesystem filesystem = new FakeProjectFilesystem();
    private ImmutableMap<String, String> environment = ImmutableMap.copyOf(System.getenv());
    private ImmutableMap<String, ImmutableMap<String, String>> sections = EMPTY_SECTIONS;
    private Architecture architecture = Architecture.detect();
    private Platform platform = Platform.detect();

    public Builder setArchitecture(Architecture architecture) {
      this.architecture = architecture;
      return this;
    }

    public Builder setEnvironment(ImmutableMap<String, String> environment) {
      this.environment = environment;
      return this;
    }

    public Builder setFilesystem(ProjectFilesystem filesystem) {
      this.filesystem = filesystem;
      return this;
    }

    public Builder setPlatform(Platform platform) {
      this.platform = platform;
      return this;
    }

    public Builder setSections(ImmutableMap<String, ImmutableMap<String, String>> sections) {
      this.sections = sections;
      return this;
    }

    public Builder setSections(String... iniFileLines) {
      try {
        sections = Inis.read(
            new StringReader(
                Joiner.on(
                    "\n").join(Arrays.asList(iniFileLines))));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return this;
    }

    public BuckConfig build() {
      return new BuckConfig(
          new Config(sections),
          filesystem,
          architecture,
          platform,
          environment);
    }
  }
}
