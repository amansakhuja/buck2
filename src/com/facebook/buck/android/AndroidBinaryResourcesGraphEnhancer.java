/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import org.immutables.value.Value;

class AndroidBinaryResourcesGraphEnhancer {
  static final Flavor RESOURCES_FILTER_FLAVOR = InternalFlavor.of("resources_filter");
  static final Flavor AAPT_PACKAGE_FLAVOR = InternalFlavor.of("aapt_package");
  static final Flavor AAPT2_LINK_FLAVOR = InternalFlavor.of("aapt2_link");
  static final Flavor PACKAGE_STRING_ASSETS_FLAVOR = InternalFlavor.of("package_string_assets");
  private static final Flavor MERGE_ASSETS_FLAVOR = InternalFlavor.of("merge_assets");
  static final Flavor GENERATE_RDOT_JAVA_FLAVOR = InternalFlavor.of("generate_rdot_java");
  private static final Flavor SPLIT_RESOURCES_FLAVOR = InternalFlavor.of("split_resources");
  static final Flavor GENERATE_STRING_SOURCE_MAP_FLAVOR =
      InternalFlavor.of("generate_string_source_map");

  private final SourcePathRuleFinder ruleFinder;
  private final FilterResourcesStep.ResourceFilter resourceFilter;
  private final ResourcesFilter.ResourceCompressionMode resourceCompressionMode;
  private final ImmutableSet<String> locales;
  private final BuildTarget buildTarget;
  private final ProjectFilesystem projectFilesystem;
  private final BuildRuleResolver ruleResolver;
  private final AndroidBinary.AaptMode aaptMode;
  private final SourcePath manifest;
  private final Optional<String> resourceUnionPackage;
  private final boolean shouldBuildStringSourceMap;
  private final boolean skipCrunchPngs;
  private final boolean includesVectorDrawables;
  private final EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes;
  private final ManifestEntries manifestEntries;
  private final BuildTarget originalBuildTarget;
  private final Optional<Arg> postFilterResourcesCmd;
  private final boolean exopackageForResources;
  private final boolean noAutoVersionResources;

  public AndroidBinaryResourcesGraphEnhancer(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver ruleResolver,
      BuildTarget originalBuildTarget,
      boolean exopackageForResources,
      SourcePath manifest,
      AndroidBinary.AaptMode aaptMode,
      FilterResourcesStep.ResourceFilter resourceFilter,
      ResourcesFilter.ResourceCompressionMode resourceCompressionMode,
      ImmutableSet<String> locales,
      Optional<String> resourceUnionPackage,
      boolean shouldBuildStringSourceMap,
      boolean skipCrunchPngs,
      boolean includesVectorDrawables,
      EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes,
      ManifestEntries manifestEntries,
      Optional<Arg> postFilterResourcesCmd,
      boolean noAutoVersionResources) {
    this.buildTarget = buildTarget;
    this.projectFilesystem = projectFilesystem;
    this.ruleResolver = ruleResolver;
    this.ruleFinder = new SourcePathRuleFinder(ruleResolver);
    this.exopackageForResources = exopackageForResources;
    this.resourceFilter = resourceFilter;
    this.resourceCompressionMode = resourceCompressionMode;
    this.locales = locales;
    this.aaptMode = aaptMode;
    this.manifest = manifest;
    this.resourceUnionPackage = resourceUnionPackage;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.skipCrunchPngs = skipCrunchPngs;
    this.includesVectorDrawables = includesVectorDrawables;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.manifestEntries = manifestEntries;
    this.originalBuildTarget = originalBuildTarget;
    this.postFilterResourcesCmd = postFilterResourcesCmd;
    this.noAutoVersionResources = noAutoVersionResources;
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractAndroidBinaryResourcesGraphEnhancementResult {
    SourcePath getPathToRDotTxt();

    Optional<SourcePath> getRDotJavaDir();

    SourcePath getPrimaryResourcesApkPath();

    SourcePath getAndroidManifestXml();

    SourcePath getAaptGeneratedProguardConfigFile();

    Optional<PackageStringAssets> getPackageStringAssets();

    ImmutableList<SourcePath> getPrimaryApkAssetZips();

    ImmutableList<SourcePath> getExoResources();
  }

  AndroidBinaryResourcesGraphEnhancementResult enhance(
      AndroidPackageableCollection packageableCollection) {
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        packageableCollection.getResourceDetails();

    ImmutableSortedSet<BuildRule> resourceRules =
        getTargetsAsRules(resourceDetails.getResourcesWithNonEmptyResDir());

    ImmutableCollection<BuildRule> rulesWithResourceDirectories =
        ruleFinder.filterBuildRuleInputs(resourceDetails.getResourceDirectories());

    FilteredResourcesProvider filteredResourcesProvider;
    boolean needsResourceFiltering =
        resourceFilter.isEnabled()
            || resourceCompressionMode.isStoreStringsAsAssets()
            || !locales.isEmpty();

    if (needsResourceFiltering) {
      ResourcesFilter resourcesFilter =
          createResourcesFilter(resourceDetails, resourceRules, rulesWithResourceDirectories);
      ruleResolver.addToIndex(resourcesFilter);
      filteredResourcesProvider = resourcesFilter;
      resourceRules = ImmutableSortedSet.of(resourcesFilter);
    } else {
      filteredResourcesProvider =
          new IdentityResourcesProvider(resourceDetails.getResourceDirectories());
    }

    AaptOutputInfo aaptOutputInfo;
    switch (aaptMode) {
      case AAPT1:
        {
          // Create the AaptPackageResourcesBuildable.
          AaptPackageResources aaptPackageResources =
              createAaptPackageResources(resourceDetails, filteredResourcesProvider);
          ruleResolver.addToIndex(aaptPackageResources);
          aaptOutputInfo = aaptPackageResources.getAaptOutputInfo();
        }
        break;

      case AAPT2:
        {
          Aapt2Link aapt2Link =
              createAapt2Link(
                  resourceDetails,
                  needsResourceFiltering
                      ? Optional.of(filteredResourcesProvider)
                      : Optional.empty());
          ruleResolver.addToIndex(aapt2Link);
          aaptOutputInfo = aapt2Link.getAaptOutputInfo();
        }
        break;

      default:
        throw new RuntimeException("Unexpected aaptMode: " + aaptMode);
    }

    Optional<PackageStringAssets> packageStringAssets = Optional.empty();
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      // TODO(cjhopman): we should be able to support this in exo-for-resources
      if (exopackageForResources) {
        throw new HumanReadableException(
            "exopackage_modes and resource_compression_mode for android_binary %s are "
                + "incompatible. Either remove %s from exopackage_modes or disable storing strings "
                + "as assets.",
            buildTarget, AndroidBinary.ExopackageMode.RESOURCES);
      }
      packageStringAssets =
          Optional.of(
              createPackageStringAssets(
                  resourceRules,
                  rulesWithResourceDirectories,
                  filteredResourcesProvider,
                  aaptOutputInfo));
      ruleResolver.addToIndex(packageStringAssets.get());
    }
    AndroidBinaryResourcesGraphEnhancementResult.Builder resultBuilder =
        AndroidBinaryResourcesGraphEnhancementResult.builder();
    resultBuilder.setPackageStringAssets(packageStringAssets);

    SourcePath pathToRDotTxt;
    if (exopackageForResources) {
      MergeAssets mergeAssets =
          createMergeAssetsRule(packageableCollection.getAssetsDirectories(), Optional.empty());
      SplitResources splitResources =
          createSplitResourcesRule(
              aaptOutputInfo.getPrimaryResourcesApkPath(), aaptOutputInfo.getPathToRDotTxt());

      ruleResolver.addToIndex(mergeAssets);
      ruleResolver.addToIndex(splitResources);

      pathToRDotTxt = splitResources.getPathToRDotTxt();
      resultBuilder.setPrimaryResourcesApkPath(splitResources.getPathToPrimaryResources());
      resultBuilder.addExoResources(splitResources.getPathToExoResources());
      resultBuilder.addExoResources(mergeAssets.getSourcePathToOutput());

      ruleResolver.addToIndex(splitResources);
      ruleResolver.addToIndex(mergeAssets);
    } else {
      MergeAssets mergeAssets =
          createMergeAssetsRule(
              packageableCollection.getAssetsDirectories(),
              Optional.of(aaptOutputInfo.getPrimaryResourcesApkPath()));
      ruleResolver.addToIndex(mergeAssets);

      pathToRDotTxt = aaptOutputInfo.getPathToRDotTxt();
      resultBuilder.setPrimaryResourcesApkPath(mergeAssets.getSourcePathToOutput());
      if (packageStringAssets.isPresent()) {
        resultBuilder.addPrimaryApkAssetZips(
            packageStringAssets.get().getSourcePathToStringAssetsZip());
      }
    }

    Optional<GenerateRDotJava> generateRDotJava = Optional.empty();
    if (filteredResourcesProvider.hasResources()) {
      generateRDotJava =
          Optional.of(
              createGenerateRDotJava(
                  pathToRDotTxt,
                  getTargetsAsRules(resourceDetails.getResourcesWithNonEmptyResDir()),
                  filteredResourcesProvider));
      ruleResolver.addToIndex(generateRDotJava.get());

      if (shouldBuildStringSourceMap) {
        ruleResolver.addToIndex(createGenerateStringSourceMap());
      }
    }

    return resultBuilder
        .setAaptGeneratedProguardConfigFile(aaptOutputInfo.getAaptGeneratedProguardConfigFile())
        .setAndroidManifestXml(aaptOutputInfo.getAndroidManifestXml())
        .setPathToRDotTxt(aaptOutputInfo.getPathToRDotTxt())
        .setRDotJavaDir(
            generateRDotJava.map(GenerateRDotJava::getSourcePathToGeneratedRDotJavaSrcFiles))
        .build();
  }

  private SplitResources createSplitResourcesRule(
      SourcePath aaptOutputPath, SourcePath aaptRDotTxtPath) {
    return new SplitResources(
        buildTarget.withAppendedFlavors(SPLIT_RESOURCES_FLAVOR),
        projectFilesystem,
        ruleFinder,
        aaptOutputPath,
        aaptRDotTxtPath);
  }

  private Aapt2Link createAapt2Link(
      AndroidPackageableCollection.ResourceDetails resourceDetails,
      Optional<FilteredResourcesProvider> filteredResourcesProvider) {
    ImmutableList.Builder<Aapt2Compile> compileListBuilder = ImmutableList.builder();
    if (filteredResourcesProvider.isPresent()) {
      Optional<BuildRule> resourceFilterRule =
          filteredResourcesProvider.get().getResourceFilterRule();
      Preconditions.checkState(
          resourceFilterRule.isPresent(),
          "Expected ResourceFilterRule to be present when filtered resources are present.");
      ImmutableSortedSet<BuildRule> compileDeps = ImmutableSortedSet.of(resourceFilterRule.get());
      int index = 0;
      for (SourcePath resDir : filteredResourcesProvider.get().getResDirectories()) {
        Aapt2Compile compileRule =
            new Aapt2Compile(
                buildTarget.withAppendedFlavors(InternalFlavor.of("aapt2_compile_" + index)),
                projectFilesystem,
                compileDeps,
                resDir);
        ruleResolver.addToIndex(compileRule);
        compileListBuilder.add(compileRule);
        index++;
      }
    } else {
      for (BuildTarget resTarget : resourceDetails.getResourcesWithNonEmptyResDir()) {
        compileListBuilder.add(
            (Aapt2Compile)
                ruleResolver.requireRule(
                    resTarget.withAppendedFlavors(
                        AndroidResourceDescription.AAPT2_COMPILE_FLAVOR)));
      }
    }
    return new Aapt2Link(
        buildTarget.withAppendedFlavors(AAPT2_LINK_FLAVOR),
        projectFilesystem,
        ruleFinder,
        compileListBuilder.build(),
        getTargetsAsResourceDeps(resourceDetails.getResourcesWithNonEmptyResDir()),
        manifest,
        manifestEntries,
        noAutoVersionResources);
  }

  private GenerateRDotJava createGenerateRDotJava(
      SourcePath pathToRDotTxtFile,
      ImmutableSortedSet<BuildRule> resourceDeps,
      FilteredResourcesProvider resourcesProvider) {
    return new GenerateRDotJava(
        buildTarget.withAppendedFlavors(GENERATE_RDOT_JAVA_FLAVOR),
        projectFilesystem,
        ruleFinder,
        bannedDuplicateResourceTypes,
        pathToRDotTxtFile,
        resourceUnionPackage,
        shouldBuildStringSourceMap,
        resourceDeps,
        resourcesProvider);
  }

  private GenerateStringSourceMap createGenerateStringSourceMap() {
    return new GenerateStringSourceMap(
        buildTarget.withAppendedFlavors(GENERATE_STRING_SOURCE_MAP_FLAVOR),
        projectFilesystem,
        ruleFinder);
  }

  private ResourcesFilter createResourcesFilter(
      AndroidPackageableCollection.ResourceDetails resourceDetails,
      ImmutableSortedSet<BuildRule> resourceRules,
      ImmutableCollection<BuildRule> rulesWithResourceDirectories) {
    return new ResourcesFilter(
        buildTarget.withAppendedFlavors(RESOURCES_FILTER_FLAVOR),
        projectFilesystem,
        resourceRules,
        rulesWithResourceDirectories,
        ruleFinder,
        resourceDetails.getResourceDirectories(),
        ImmutableSet.copyOf(resourceDetails.getWhitelistedStringDirectories()),
        locales,
        resourceCompressionMode,
        resourceFilter,
        postFilterResourcesCmd);
  }

  private AaptPackageResources createAaptPackageResources(
      AndroidPackageableCollection.ResourceDetails resourceDetails,
      FilteredResourcesProvider filteredResourcesProvider) {
    return new AaptPackageResources(
        buildTarget.withAppendedFlavors(AAPT_PACKAGE_FLAVOR),
        projectFilesystem,
        ruleFinder,
        ruleResolver,
        manifest,
        filteredResourcesProvider,
        getTargetsAsResourceDeps(resourceDetails.getResourcesWithNonEmptyResDir()),
        skipCrunchPngs,
        includesVectorDrawables,
        manifestEntries);
  }

  private PackageStringAssets createPackageStringAssets(
      ImmutableSortedSet<BuildRule> resourceRules,
      ImmutableCollection<BuildRule> rulesWithResourceDirectories,
      FilteredResourcesProvider filteredResourcesProvider,
      AaptOutputInfo aaptOutputInfo) {
    return new PackageStringAssets(
        buildTarget.withAppendedFlavors(
            AndroidBinaryResourcesGraphEnhancer.PACKAGE_STRING_ASSETS_FLAVOR),
        projectFilesystem,
        ruleFinder,
        resourceRules,
        rulesWithResourceDirectories,
        filteredResourcesProvider,
        locales,
        aaptOutputInfo.getPathToRDotTxt());
  }

  private MergeAssets createMergeAssetsRule(
      ImmutableSet<SourcePath> assetsDirectories, Optional<SourcePath> baseApk) {
    MergeAssets mergeAssets =
        new MergeAssets(
            buildTarget.withAppendedFlavors(MERGE_ASSETS_FLAVOR),
            projectFilesystem,
            ruleFinder,
            baseApk,
            ImmutableSortedSet.copyOf(assetsDirectories));
    ruleResolver.addToIndex(mergeAssets);
    return mergeAssets;
  }

  private ImmutableSortedSet<BuildRule> getTargetsAsRules(Collection<BuildTarget> buildTargets) {
    return BuildRules.toBuildRulesFor(originalBuildTarget, ruleResolver, buildTargets);
  }

  private ImmutableList<HasAndroidResourceDeps> getTargetsAsResourceDeps(
      Collection<BuildTarget> targets) {
    return getTargetsAsRules(targets)
        .stream()
        .map(
            input -> {
              Preconditions.checkState(input instanceof HasAndroidResourceDeps);
              return (HasAndroidResourceDeps) input;
            })
        .collect(MoreCollectors.toImmutableList());
  }
}
