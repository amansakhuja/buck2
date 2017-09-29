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

package com.facebook.buck.swift;

import com.facebook.buck.config.BuckConfig;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/** A Swift-specific "view" of BuckConfig. */
public class SwiftBuckConfig {
  private static final String SECTION_NAME = "swift";
  private static final String COMPILER_FLAGS_NAME = "compiler_flags";

  private final BuckConfig delegate;

  public SwiftBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Optional<Iterable<String>> getFlags() {
    Optional<String> value = delegate.getValue(SECTION_NAME, COMPILER_FLAGS_NAME);
    return value.map(input -> Splitter.on(" ").split(input.trim()));
  }

  public Optional<ImmutableList<String>> getFlags(String field) {
    Optional<String> value = delegate.getValue(SECTION_NAME, field);
    if (!value.isPresent()) {
      return Optional.empty();
    }
    ImmutableList.Builder<String> split = ImmutableList.builder();
    if (!value.get().trim().isEmpty()) {
      split.addAll(Splitter.on(" ").split(value.get().trim()));
    }
    return Optional.of(split.build());
  }

  public Optional<String> getVersion() {
    return delegate.getValue(SECTION_NAME, "version");
  }
}
