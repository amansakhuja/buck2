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

package com.facebook.buck.lua;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.SourceList;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

public class LuaLibraryBuilder extends AbstractNodeBuilder<LuaLibraryDescription.Arg> {

  public LuaLibraryBuilder(
      Description<LuaLibraryDescription.Arg> description,
      BuildTarget target) {
    super(description, target);
  }

  public LuaLibraryBuilder(BuildTarget target) {
    this(new LuaLibraryDescription(), target);
  }

  public LuaLibraryBuilder setBaseModule(String baseModule) {
    arg.baseModule = Optional.of(baseModule);
    return this;
  }

  public LuaLibraryBuilder setSrcs(SourceList srcs) {
    arg.srcs = Optional.of(srcs);
    return this;
  }

  public LuaLibraryBuilder setSrcs(ImmutableSortedSet<SourcePath> srcs) {
    arg.srcs = Optional.of(SourceList.ofUnnamedSources(srcs));
    return this;
  }

  public LuaLibraryBuilder setSrcs(ImmutableSortedMap<String, SourcePath> srcs) {
    arg.srcs = Optional.of(SourceList.ofNamedSources(srcs));
    return this;
  }

  public LuaLibraryBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    arg.deps = Optional.of(deps);
    return this;
  }

}
