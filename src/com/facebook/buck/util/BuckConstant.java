/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.base.Preconditions;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

public class BuckConstant {

  private static final String BUCK_OUTPUT_DIRECTORY = "buck-out";
  private static final Path BUCK_OUTPUT_PATH = Paths.get("buck-out");
  private static final Path CURRENT_VERSION_FILE =
      getBuckOutputPath().resolve(".currentversion");

  // TODO(bolinfest): The constants GEN_DIR, BIN_DIR, and ANNOTATION_DIR should be
  // package-private to the com.facebook.buck.rules directory. Currently, they are also used in the
  // com.facebook.buck.shell package, but these values should be injected into shell commands rather
  // than hardcoded therein. This ensures that shell commands stay build-rule-agnostic.

  private static final String GEN_DIR = getBuckOutputDirectory() + "/gen";
  private static final Path GEN_PATH = getBuckOutputPath().resolve("gen");

  private static final Path RES_PATH = getBuckOutputPath().resolve("res");

  private static final String SCRATCH_DIR = getBuckOutputDirectory() + "/bin";
  private static final Path SCRATCH_PATH = getBuckOutputPath().resolve("bin");

  private static final String ANNOTATION_DIR = getBuckOutputDirectory() + "/annotation";
  private static final Path ANNOTATION_PATH = getBuckOutputPath().resolve("annotation");

  private static final Path LOG_PATH = getBuckOutputPath().resolve("log");

  private static final Path BUCK_TRACE_DIR = getBuckOutputPath().resolve("log/traces");
  private static final String DEFAULT_CACHE_DIR = getBuckOutputDirectory() + "/cache";

  // We put a . at the front of the name so Spotlight doesn't try to index the contents on OS X.
  private static final String TRASH_DIR = getBuckOutputDirectory() + "/.trash";
  private static final Path TRASH_PATH = getBuckOutputPath().resolve(".trash");

  private BuckConstant() {}

  /**
   * An optional path-component for the directory where test-results are written.
   * <p>
   * See the --one-time-directory command line option in {@link com.facebook.buck.cli.TestCommand}
   * where this is used to give each parallel buck processes a unique test-results-directory
   * thereby stopping the parallel processes from interfering with each others results.
   * <p>
   * TODO(#4473736) Create a long-term non-hacky solution to this problem!
   */
  @Nullable
  public static String oneTimeTestSubdirectory = null;

  public static void setOneTimeTestSubdirectory(String oneTimeTestSubdirectory) {
    Preconditions.checkState(!oneTimeTestSubdirectory.isEmpty(), "cannot be an empty string");
    BuckConstant.oneTimeTestSubdirectory = oneTimeTestSubdirectory;
  }

  /**
   * The relative path to the directory where Buck will generate its files.
   */
  public static String getBuckOutputDirectory() {
    return BUCK_OUTPUT_DIRECTORY;
  }

  public static Path getBuckOutputPath() {
    return BUCK_OUTPUT_PATH;
  }

  /**
   * The version the buck output directory was created for
   */
  public static Path getCurrentVersionFile() {
    return CURRENT_VERSION_FILE;
  }

  public static String getGenDir() {
    return GEN_DIR;
  }

  public static Path getGenPath() {
    return GEN_PATH;
  }

  public static Path getResPath() {
    return RES_PATH;
  }

  public static String getScratchDir() {
    return SCRATCH_DIR;
  }

  public static Path getScratchPath() {
    return SCRATCH_PATH;
  }

  public static String getAnnotationDir() {
    return ANNOTATION_DIR;
  }

  public static Path getAnnotationPath() {
    return ANNOTATION_PATH;
  }

  public static Path getLogPath() {
    return LOG_PATH;
  }

  public static Path getBuckTraceDir() {
    return BUCK_TRACE_DIR;
  }

  public static String getDefaultCacheDir() {
    return DEFAULT_CACHE_DIR;
  }

  public static String getTrashDir() {
    return TRASH_DIR;
  }

  public static Path getTrashPath() {
    return TRASH_PATH;
  }
}
