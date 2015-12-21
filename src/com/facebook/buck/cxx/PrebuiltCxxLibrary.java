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

package com.facebook.buck.cxx;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.util.Map;

public class PrebuiltCxxLibrary
    extends NoopBuildRule
    implements AbstractCxxLibrary, CanProvideSharedNativeLinkTarget {

  private final BuildRuleParams params;
  private final BuildRuleResolver ruleResolver;
  private final Iterable<? extends NativeLinkable> exportedDeps;
  private final ImmutableList<Path> includeDirs;
  private final Optional<String> libDir;
  private final Optional<String> libName;
  private final Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
      exportedPreprocessorFlags;
  private final Function<CxxPlatform, Boolean> hasHeaders;
  private final Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags;
  private final Optional<String> soname;
  private final boolean linkWithoutSoname;
  private final boolean forceStatic;
  private final boolean headerOnly;
  private final boolean linkWhole;
  private final boolean provided;

  private final Map<Pair<Flavor, HeaderVisibility>, ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      cxxPreprocessorInputCache = Maps.newHashMap();

  public PrebuiltCxxLibrary(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      Iterable<? extends NativeLinkable> exportedDeps,
      ImmutableList<Path> includeDirs,
      Optional<String> libDir,
      Optional<String> libName,
      Function<? super CxxPlatform, ImmutableMultimap<CxxSource.Type, String>>
          exportedPreprocessorFlags,
      Function<? super CxxPlatform, ImmutableList<String>> exportedLinkerFlags,
      Optional<String> soname,
      boolean linkWithoutSoname,
      boolean forceStatic,
      boolean headerOnly,
      boolean linkWhole,
      boolean provided,
      Function<CxxPlatform, Boolean> hasHeaders) {
    super(params, pathResolver);
    Preconditions.checkArgument(!forceStatic || !provided);
    this.params = params;
    this.ruleResolver = ruleResolver;
    this.exportedDeps = exportedDeps;
    this.includeDirs = includeDirs;
    this.libDir = libDir;
    this.libName = libName;
    this.exportedPreprocessorFlags = exportedPreprocessorFlags;
    this.hasHeaders = hasHeaders;
    this.exportedLinkerFlags = exportedLinkerFlags;
    this.soname = soname;
    this.linkWithoutSoname = linkWithoutSoname;
    this.forceStatic = forceStatic;
    this.headerOnly = headerOnly;
    this.linkWhole = linkWhole;
    this.provided = provided;
  }

  protected String getSoname(CxxPlatform cxxPlatform) {
    return PrebuiltCxxLibraryDescription.getSoname(getBuildTarget(), cxxPlatform, soname, libName);
  }

  /**
   * Makes sure all build rules needed to produce the shared library are added to the action
   * graph.
   *
   * @return the {@link SourcePath} representing the actual shared library.
   */
  private SourcePath requireSharedLibrary(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    Path sharedLibraryPath =
        PrebuiltCxxLibraryDescription.getSharedLibraryPath(
            getBuildTarget(),
            cxxPlatform,
            libDir,
            libName);

    // If the shared library is prebuilt, just return a reference to it.
    if (params.getProjectFilesystem().exists(sharedLibraryPath)) {
      return new PathSourcePath(params.getProjectFilesystem(), sharedLibraryPath);
    }

    // Otherwise, generate it's build rule.
    BuildRule sharedLibrary =
        ruleResolver.requireRule(
            getBuildTarget().withFlavors(
                cxxPlatform.getFlavor(),
                CxxDescriptionEnhancer.SHARED_FLAVOR));

    return new BuildTargetSourcePath(sharedLibrary.getBuildTarget());
  }

  /**
   * @return the {@link Path} representing the actual static PIC library.
   */
  private Optional<Path> getStaticPicLibrary(CxxPlatform cxxPlatform) {
    Path staticPicLibraryPath =
        PrebuiltCxxLibraryDescription.getStaticPicLibraryPath(
            getBuildTarget(),
            cxxPlatform,
            libDir,
            libName);
    if (params.getProjectFilesystem().exists(staticPicLibraryPath)) {
      return Optional.of(staticPicLibraryPath);
    }

    // If a specific static-pic variant isn't available, then just use the static variant.
    Path staticLibraryPath =
        PrebuiltCxxLibraryDescription.getStaticLibraryPath(
            getBuildTarget(),
            cxxPlatform,
            libDir,
            libName);
    if (params.getProjectFilesystem().exists(staticLibraryPath)) {
      return Optional.of(staticLibraryPath);
    }

    return Optional.absent();
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

    switch (headerVisibility) {
      case PUBLIC:
        if (Preconditions.checkNotNull(hasHeaders.apply(cxxPlatform))) {
          CxxPreprocessables.addHeaderSymlinkTree(
              builder,
              getBuildTarget(),
              ruleResolver,
              cxxPlatform.getFlavor(),
              headerVisibility,
              CxxPreprocessables.IncludeType.SYSTEM);
        }
        builder.putAllPreprocessorFlags(
            Preconditions.checkNotNull(exportedPreprocessorFlags.apply(cxxPlatform)));
        // Just pass the include dirs as system includes.
        builder.addAllSystemIncludeRoots(
            Iterables.transform(includeDirs, getProjectFilesystem().getAbsolutifier()));
        return builder.build();
      case PRIVATE:
        return builder.build();
    }

    // We explicitly don't put this in a default statement because we
    // want the compiler to warn if someone modifies the HeaderVisibility enum.
    throw new RuntimeException("Invalid header visibility: " + headerVisibility);
  }


  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) throws NoSuchBuildTargetException {
    Pair<Flavor, HeaderVisibility> key = new Pair<>(cxxPlatform.getFlavor(), headerVisibility);
    ImmutableMap<BuildTarget, CxxPreprocessorInput> result = cxxPreprocessorInputCache.get(key);
    if (result == null) {
      Map<BuildTarget, CxxPreprocessorInput> builder = Maps.newLinkedHashMap();
      builder.put(
          getBuildTarget(),
          getCxxPreprocessorInput(cxxPlatform, headerVisibility));
      for (BuildRule dep : getDeps()) {
        if (dep instanceof CxxPreprocessorDep) {
          builder.putAll(
              ((CxxPreprocessorDep) dep).getTransitiveCxxPreprocessorInput(
                  cxxPlatform,
                  headerVisibility));
        }
      }
      result = ImmutableMap.copyOf(builder);
      cxxPreprocessorInputCache.put(key, result);
    }
    return result;
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
    return FluentIterable.from(getDeclaredDeps())
        .filter(NativeLinkable.class);
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(CxxPlatform cxxPlatform) {
    return exportedDeps;
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type) throws NoSuchBuildTargetException {
    // Build the library path and linker arguments that we pass through the
    // {@link NativeLinkable} interface for linking.
    ImmutableList.Builder<Arg> linkerArgsBuilder = ImmutableList.builder();
    linkerArgsBuilder.addAll(
        StringArg.from(Preconditions.checkNotNull(exportedLinkerFlags.apply(cxxPlatform))));
    if (!headerOnly) {
      if (type == Linker.LinkableDepType.SHARED) {
        Preconditions.checkState(getPreferredLinkage(cxxPlatform) != Linkage.STATIC);
        final SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform);
        if (linkWithoutSoname) {
          if (!(sharedLibrary instanceof PathSourcePath)) {
            throw new HumanReadableException(
                "%s: can only link prebuilt DSOs without sonames",
                getBuildTarget());
          }
          linkerArgsBuilder.add(new RelativeLinkArg((PathSourcePath) sharedLibrary));
        } else {
          linkerArgsBuilder.add(
              new SourcePathArg(getResolver(), requireSharedLibrary(cxxPlatform)));
        }
      } else {
        Preconditions.checkState(getPreferredLinkage(cxxPlatform) != Linkage.SHARED);
        Path staticLibraryPath =
            type == Linker.LinkableDepType.STATIC_PIC ?
                getStaticPicLibrary(cxxPlatform).get() :
                PrebuiltCxxLibraryDescription.getStaticLibraryPath(
                    getBuildTarget(),
                    cxxPlatform,
                    libDir,
                    libName);
        SourcePathArg staticLibrary =
            new SourcePathArg(
                getResolver(),
                new PathSourcePath(getProjectFilesystem(), staticLibraryPath));
        if (linkWhole) {
          Linker linker = cxxPlatform.getLd();
          linkerArgsBuilder.addAll(linker.linkWhole(staticLibrary));
        } else {
          linkerArgsBuilder.add(staticLibrary);
        }
      }
    }
    final ImmutableList<Arg> linkerArgs = linkerArgsBuilder.build();

    return NativeLinkableInput.of(
        linkerArgs,
        ImmutableSet.<FrameworkPath>of(),
        ImmutableSet.<FrameworkPath>of());
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    if (forceStatic) {
      return Linkage.STATIC;
    }
    if (provided || !getStaticPicLibrary(cxxPlatform).isPresent()) {
      return Linkage.SHARED;
    }
    return Linkage.ANY;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(params.getDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addNativeLinkable(this);
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    String resolvedSoname = getSoname(cxxPlatform);
    ImmutableMap.Builder<String, SourcePath> solibs = ImmutableMap.builder();
    if (!headerOnly && !provided && getPreferredLinkage(cxxPlatform) != Linkage.STATIC) {
      SourcePath sharedLibrary = requireSharedLibrary(cxxPlatform);
      solibs.put(resolvedSoname, sharedLibrary);
    }
    return solibs.build();
  }

  @Override
  public Optional<SharedNativeLinkTarget> getSharedNativeLinkTarget(CxxPlatform cxxPlatform) {
    if (getPreferredLinkage(cxxPlatform) == Linkage.SHARED) {
      return Optional.absent();
    }
    return Optional.<SharedNativeLinkTarget>of(
        new SharedNativeLinkTarget() {
          @Override
          public BuildTarget getBuildTarget() {
            return PrebuiltCxxLibrary.this.getBuildTarget();
          }
          @Override
          public Iterable<? extends NativeLinkable> getSharedNativeLinkTargetDeps(
              CxxPlatform cxxPlatform) {
            return Iterables.concat(
                getNativeLinkableDeps(cxxPlatform),
                getNativeLinkableExportedDeps(cxxPlatform));
          }
          @Override
          public String getSharedNativeLinkTargetLibraryName(CxxPlatform cxxPlatform) {
            return getSoname(cxxPlatform);
          }
          @Override
          public NativeLinkableInput getSharedNativeLinkTargetInput(CxxPlatform cxxPlatform)
              throws NoSuchBuildTargetException {
            return NativeLinkableInput.builder()
                .addAllArgs(StringArg.from(exportedLinkerFlags.apply(cxxPlatform)))
                .addAllArgs(
                    cxxPlatform.getLd().linkWhole(
                        new SourcePathArg(
                            getResolver(),
                            new PathSourcePath(
                                params.getProjectFilesystem(),
                                getStaticPicLibrary(cxxPlatform).get()))))
                .build();
          }
        });
  }

}
