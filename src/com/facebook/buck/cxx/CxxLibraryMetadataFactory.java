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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.google.common.collect.Multimaps;
import java.util.Map;
import java.util.Optional;

public class CxxLibraryMetadataFactory {
  private final ToolchainProvider toolchainProvider;

  public CxxLibraryMetadataFactory(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxLibraryDescriptionArg args,
      Class<U> metadataClass) {

    Map.Entry<Flavor, CxxLibraryDescription.MetadataType> type =
        CxxLibraryDescription.METADATA_TYPE
            .getFlavorAndValue(buildTarget)
            .orElseThrow(IllegalArgumentException::new);
    BuildTarget baseTarget = buildTarget.withoutFlavors(type.getKey());

    switch (type.getValue()) {
      case CXX_HEADERS:
        {
          Optional<CxxHeaders> symlinkTree = Optional.empty();
          if (!args.getExportedHeaders().isEmpty()) {
            HeaderMode mode = CxxLibraryDescription.HEADER_MODE.getRequiredValue(buildTarget);
            baseTarget = baseTarget.withoutFlavors(mode.getFlavor());
            symlinkTree =
                Optional.of(
                    CxxSymlinkTreeHeaders.from(
                        (HeaderSymlinkTree)
                            resolver.requireRule(
                                baseTarget
                                    .withoutFlavors(CxxLibraryDescription.LIBRARY_TYPE.getFlavors())
                                    .withAppendedFlavors(
                                        CxxLibraryDescription.Type.EXPORTED_HEADERS.getFlavor(),
                                        mode.getFlavor())),
                        CxxPreprocessables.IncludeType.LOCAL));
          }
          return symlinkTree.map(metadataClass::cast);
        }

      case CXX_PREPROCESSOR_INPUT:
        {
          Map.Entry<Flavor, CxxPlatform> platform =
              getCxxPlatformsProvider()
                  .getCxxPlatforms()
                  .getFlavorAndValue(buildTarget)
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              String.format(
                                  "%s: cannot extract platform from target flavors (available platforms: %s)",
                                  buildTarget,
                                  getCxxPlatformsProvider().getCxxPlatforms().getFlavors())));
          Map.Entry<Flavor, HeaderVisibility> visibility =
              CxxLibraryDescription.HEADER_VISIBILITY
                  .getFlavorAndValue(buildTarget)
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              String.format(
                                  "%s: cannot extract visibility from target flavors (available options: %s)",
                                  buildTarget,
                                  CxxLibraryDescription.HEADER_VISIBILITY.getFlavors())));
          baseTarget = baseTarget.withoutFlavors(platform.getKey(), visibility.getKey());

          CxxPreprocessorInput.Builder cxxPreprocessorInputBuilder = CxxPreprocessorInput.builder();

          // TODO(agallagher): We currently always add exported flags and frameworks to the
          // preprocessor input to mimic existing behavior, but this should likely be fixed.
          cxxPreprocessorInputBuilder.putAllPreprocessorFlags(
              Multimaps.transformValues(
                  CxxFlags.getLanguageFlagsWithMacros(
                      args.getExportedPreprocessorFlags(),
                      args.getExportedPlatformPreprocessorFlags(),
                      args.getExportedLangPreprocessorFlags(),
                      platform.getValue()),
                  f ->
                      CxxDescriptionEnhancer.toStringWithMacrosArgs(
                          buildTarget, cellRoots, resolver, platform.getValue(), f)));
          cxxPreprocessorInputBuilder.addAllFrameworks(args.getFrameworks());

          if (visibility.getValue() == HeaderVisibility.PRIVATE && !args.getHeaders().isEmpty()) {
            HeaderSymlinkTree symlinkTree =
                (HeaderSymlinkTree)
                    resolver.requireRule(
                        baseTarget.withAppendedFlavors(
                            platform.getKey(), CxxLibraryDescription.Type.HEADERS.getFlavor()));
            cxxPreprocessorInputBuilder.addIncludes(
                CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
          }

          if (visibility.getValue() == HeaderVisibility.PUBLIC) {

            // Add platform-agnostic headers.
            queryMetadataCxxHeaders(
                    resolver,
                    baseTarget,
                    CxxDescriptionEnhancer.getHeaderModeForPlatform(
                        resolver,
                        platform.getValue(),
                        args.getXcodePublicHeadersSymlinks()
                            .orElse(platform.getValue().getPublicHeadersSymlinksEnabled())))
                .ifPresent(cxxPreprocessorInputBuilder::addIncludes);

            // Add platform-specific headers.
            if (!args.getExportedPlatformHeaders()
                .getMatchingValues(platform.getKey().toString())
                .isEmpty()) {
              HeaderSymlinkTree symlinkTree =
                  (HeaderSymlinkTree)
                      resolver.requireRule(
                          baseTarget
                              .withoutFlavors(CxxLibraryDescription.LIBRARY_TYPE.getFlavors())
                              .withAppendedFlavors(
                                  CxxLibraryDescription.Type.EXPORTED_HEADERS.getFlavor(),
                                  platform.getKey()));
              cxxPreprocessorInputBuilder.addIncludes(
                  CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
            }

            if (!args.getRawHeaders().isEmpty()) {
              cxxPreprocessorInputBuilder.addIncludes(CxxRawHeaders.of(args.getRawHeaders()));
            }
          }

          CxxPreprocessorInput cxxPreprocessorInput = cxxPreprocessorInputBuilder.build();
          return Optional.of(cxxPreprocessorInput).map(metadataClass::cast);
        }
    }

    throw new IllegalStateException(String.format("unhandled metadata type: %s", type.getValue()));
  }

  /**
   * Convenience function to query the {@link CxxHeaders} metadata of a target.
   *
   * <p>Use this function instead of constructing the BuildTarget manually.
   */
  private static Optional<CxxHeaders> queryMetadataCxxHeaders(
      BuildRuleResolver resolver, BuildTarget baseTarget, HeaderMode mode) {
    return resolver.requireMetadata(
        baseTarget.withAppendedFlavors(
            CxxLibraryDescription.MetadataType.CXX_HEADERS.getFlavor(), mode.getFlavor()),
        CxxHeaders.class);
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }
}
