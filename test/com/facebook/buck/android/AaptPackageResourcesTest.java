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

package com.facebook.buck.android;

import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;

public class AaptPackageResourcesTest {

  @Test
  public void initializeFromDiskDoesNotAccessOutputFromDeps() throws IOException {
    FilteredResourcesProvider resourcesProvider =
        new FilteredResourcesProvider() {
          @Override
          public ImmutableList<Path> getResDirectories() {
            throw new AssertionError("unexpected call to getResDirectories");
          }
          @Override
          public ImmutableList<Path> getStringFiles() {
            throw new AssertionError("unexpected call to getStringFiles");
          }
        };

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:target"))
            .build();
    AaptPackageResources aaptPackageResources =
        new AaptPackageResources(
            params,
            /* manifest */ new FakeSourcePath("facebook/base/AndroidManifest.xml"),
            resourcesProvider,
            ImmutableList.of(),
            ImmutableSet.of(),
            /* resourceUnionPackage */ Optional.empty(),
            PackageType.DEBUG,
            /* shouldBuildStringSourceMap */ false,
            /* skipCrunchPngs */ false,
            /* includesVectorDrawables */ false,
            /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
            /* manifestEntries */ ManifestEntries.empty());

    FakeOnDiskBuildInfo onDiskBuildInfo =
        new FakeOnDiskBuildInfo()
            .putMetadata(
                AaptPackageResources.RESOURCE_PACKAGE_HASH_KEY,
                "0123456789012345678901234567890123456789")
            .putMetadata(
                AaptPackageResources.FILTERED_RESOURCE_DIRS_KEY,
                ImmutableList.of());
    aaptPackageResources.initializeFromDisk(onDiskBuildInfo);
  }

}
