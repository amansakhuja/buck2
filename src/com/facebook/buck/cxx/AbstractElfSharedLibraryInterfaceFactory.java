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

import com.facebook.buck.cxx.toolchain.ElfSharedLibraryInterfaceParams;
import com.facebook.buck.cxx.toolchain.SharedLibraryInterfaceFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.immutables.BuckStylePackageVisibleTuple;
import org.immutables.value.Value;

@Value.Immutable
@BuckStylePackageVisibleTuple
abstract class AbstractElfSharedLibraryInterfaceFactory implements SharedLibraryInterfaceFactory {

  abstract ToolProvider getObjcopy();

  abstract boolean isRemoveUndefinedSymbols();

  @Override
  public final BuildRule createSharedInterfaceLibrary(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePath library) {
    return ElfSharedLibraryInterface.from(
        target,
        projectFilesystem,
        pathResolver,
        ruleFinder,
        getObjcopy().resolve(resolver),
        library,
        isRemoveUndefinedSymbols());
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps() {
    return getObjcopy().getParseTimeDeps();
  }

  public static ElfSharedLibraryInterfaceFactory from(ElfSharedLibraryInterfaceParams params) {
    return ElfSharedLibraryInterfaceFactory.of(
        params.getObjcopy(), params.isRemoveUndefinedSymbols());
  }
}
