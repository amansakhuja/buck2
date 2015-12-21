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

package com.facebook.buck.jvm.java.intellij;

import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

/**
 * A path which contains a set of sources we wish to present to IntelliJ.
 */
public class SourceFolder extends InclusiveFolder {

  public static final IJFolderFactory FACTORY = new IJFolderFactory() {
    @Override
    public IjFolder create(
        Path path, boolean wantsPrefix, ImmutableSortedSet<Path> inputs) {
      return new SourceFolder(path, wantsPrefix, inputs);
    }
  };

  SourceFolder(Path path, boolean wantsPackagePrefix, ImmutableSortedSet<Path> inputs) {
    super(path, wantsPackagePrefix, inputs);
  }

  SourceFolder(Path path, boolean wantsPackagePrefix) {
    super(path, wantsPackagePrefix);
  }

  SourceFolder(Path path) {
    super(path);
  }

  @Override
  public IJFolderFactory getFactory() {
    return FACTORY;
  }

  @Override
  public IjFolder merge(IjFolder otherFolder) {
    checkMergeConditions(otherFolder);

    if (otherFolder == this) {
      return this;
    }

    return new SourceFolder(
        otherFolder.getPath(),
        getWantsPackagePrefix() || otherFolder.getWantsPackagePrefix(),
        combineInputs(this, otherFolder)
    );
  }
}
