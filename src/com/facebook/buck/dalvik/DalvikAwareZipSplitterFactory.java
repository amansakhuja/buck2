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

package com.facebook.buck.dalvik;

import com.google.common.base.Predicate;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;

public class DalvikAwareZipSplitterFactory implements ZipSplitterFactory {

  private final long linearAllocLimit;

  public DalvikAwareZipSplitterFactory(long linearAllocLimit) {
    this.linearAllocLimit = linearAllocLimit;
  }

  @Override
  public ZipSplitter newInstance(Set<Path> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      Predicate<String> requiredInPrimaryZip,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      ZipSplitter.CanaryStrategy canaryStrategy,
      File reportDir) {
    return DalvikAwareZipSplitter.splitZip(inFiles,
        outPrimary,
        outSecondaryDir,
        secondaryPattern,
        linearAllocLimit,
        requiredInPrimaryZip,
        dexSplitStrategy,
        canaryStrategy,
        reportDir);
  }
}
