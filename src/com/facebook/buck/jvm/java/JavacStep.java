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

package com.facebook.buck.jvm.java;

import com.facebook.buck.event.CompilerErrorEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.message_ipc.Connection;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.CapturingPrintStream;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Command used to compile java libraries with a variety of ways to handle dependencies. */
public class JavacStep implements Step {

  private final Path outputDirectory;

  private final ClassUsageFileWriter usedClassesFileWriter;

  private final Optional<Path> generatedCodeDirectory;

  private final Optional<Path> workingDirectory;

  private final ImmutableSortedSet<Path> javaSourceFilePaths;

  private final Path pathToSrcsList;

  private final JavacOptions javacOptions;

  private final ImmutableSortedSet<Path> declaredClasspathEntries;

  private final BuildTarget invokingRule;

  private final SourcePathResolver resolver;

  private final ProjectFilesystem filesystem;

  private final Javac javac;

  private final ClasspathChecker classpathChecker;

  private final Optional<DirectToJarOutputSettings> directToJarOutputSettings;

  @Nullable private final Path abiJar;

  public JavacStep(
      Path outputDirectory,
      ClassUsageFileWriter usedClassesFileWriter,
      Optional<Path> generatedCodeDirectory,
      Optional<Path> workingDirectory,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      ImmutableSortedSet<Path> declaredClasspathEntries,
      Javac javac,
      JavacOptions javacOptions,
      BuildTarget invokingRule,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ClasspathChecker classpathChecker,
      Optional<DirectToJarOutputSettings> directToJarOutputSettings,
      @Nullable Path abiJar) {
    this.outputDirectory = outputDirectory;
    this.usedClassesFileWriter = usedClassesFileWriter;
    this.generatedCodeDirectory = generatedCodeDirectory;
    this.workingDirectory = workingDirectory;
    this.javaSourceFilePaths = javaSourceFilePaths;
    this.pathToSrcsList = pathToSrcsList;
    this.javacOptions = javacOptions;
    this.declaredClasspathEntries = declaredClasspathEntries;
    this.javac = javac;
    this.invokingRule = invokingRule;
    this.resolver = resolver;
    this.filesystem = filesystem;
    this.classpathChecker = classpathChecker;
    this.directToJarOutputSettings = directToJarOutputSettings;
    this.abiJar = abiJar;
  }

  @Override
  public final StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    return tryBuildWithFirstOrderDeps(context, filesystem);
  }

  private StepExecutionResult tryBuildWithFirstOrderDeps(
      ExecutionContext context, ProjectFilesystem filesystem)
      throws InterruptedException, IOException {
    javacOptions.validateOptions(classpathChecker::validateClasspath);

    Verbosity verbosity =
        context.getVerbosity().isSilent() ? Verbosity.STANDARD_INFORMATION : context.getVerbosity();
    try (CapturingPrintStream stdout = new CapturingPrintStream();
        CapturingPrintStream stderr = new CapturingPrintStream();
        ExecutionContext firstOrderContext =
            context.createSubContext(stdout, stderr, Optional.of(verbosity));
        Connection<OutOfProcessJavacConnectionInterface> connection =
            OutOfProcessConnectionFactory.connectionForOutOfProcessBuild(
                context, filesystem, getJavac(), invokingRule)) {
      JavacExecutionContext javacExecutionContext =
          JavacExecutionContext.of(
              new JavacEventSinkToBuckEventBusBridge(firstOrderContext.getBuckEventBus()),
              stderr,
              firstOrderContext.getClassLoaderCache(),
              verbosity,
              firstOrderContext.getCellPathResolver(),
              firstOrderContext.getJavaPackageFinder(),
              filesystem,
              usedClassesFileWriter,
              firstOrderContext.getEnvironment(),
              firstOrderContext.getProcessExecutor(),
              getAbsolutePathsForJavacInputs(getJavac()),
              directToJarOutputSettings);
      return performBuild(context, stdout, stderr, getJavac(), javacExecutionContext);
    }
  }

  private StepExecutionResult performBuild(
      ExecutionContext context,
      CapturingPrintStream stdout,
      CapturingPrintStream stderr,
      Javac javac,
      JavacExecutionContext javacExecutionContext)
      throws InterruptedException {
    ImmutableList<JavacPluginJsr199Fields> pluginFields =
        ImmutableList.copyOf(
            javacOptions
                .getAnnotationProcessingParams()
                .getAnnotationProcessors(filesystem, resolver)
                .stream()
                .map(ResolvedJavacPluginProperties::getJavacPluginJsr199Fields)
                .collect(Collectors.toList()));
    int declaredDepsBuildResult;
    String firstOrderStdout;
    String firstOrderStderr;
    Optional<String> returnedStderr;
    try (Javac.Invocation invocation =
        javac.newBuildInvocation(
            javacExecutionContext,
            invokingRule,
            getOptions(context, declaredClasspathEntries),
            pluginFields,
            javaSourceFilePaths,
            pathToSrcsList,
            workingDirectory,
            javacOptions.getCompilationMode())) {
      if (abiJar != null) {
        declaredDepsBuildResult =
            invocation.buildSourceAbiJar(filesystem.resolve(Preconditions.checkNotNull(abiJar)));
      } else {
        declaredDepsBuildResult = invocation.buildClasses();
      }
    }
    firstOrderStdout = stdout.getContentsAsString(Charsets.UTF_8);
    firstOrderStderr = stderr.getContentsAsString(Charsets.UTF_8);
    if (declaredDepsBuildResult != 0) {
      returnedStderr = processBuildFailure(context, firstOrderStdout, firstOrderStderr);
    } else {
      returnedStderr = Optional.empty();
    }
    return StepExecutionResult.of(declaredDepsBuildResult, returnedStderr);
  }

  private Optional<String> processBuildFailure(
      ExecutionContext context, String firstOrderStdout, String firstOrderStderr) {
    ImmutableList.Builder<String> errorMessage = ImmutableList.builder();
    errorMessage.add(firstOrderStderr);

    ImmutableSet<String> suggestions = ImmutableSet.of();
    CompilerErrorEvent evt =
        CompilerErrorEvent.create(
            invokingRule, firstOrderStderr, CompilerErrorEvent.CompilerType.Java, suggestions);
    context.postEvent(evt);

    if (!firstOrderStdout.isEmpty()) {
      context.postEvent(ConsoleEvent.info("%s", firstOrderStdout));
    }
    return Optional.of(Joiner.on("\n").join(errorMessage.build()));
  }

  private ImmutableList<Path> getAbsolutePathsForJavacInputs(Javac javac) {
    return javac
        .getInputs()
        .stream()
        .map(resolver::getAbsolutePath)
        .collect(MoreCollectors.toImmutableList());
  }

  @VisibleForTesting
  Javac getJavac() {
    return javac;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    String description =
        getJavac()
            .getDescription(
                getOptions(context, getClasspathEntries()), javaSourceFilePaths, pathToSrcsList);

    if (directToJarOutputSettings.isPresent()) {
      DirectToJarOutputSettings directToJarOutputSettings = this.directToJarOutputSettings.get();
      Optional<Path> manifestFile = directToJarOutputSettings.getManifestFile();
      ImmutableSortedSet<Path> entriesToJar = directToJarOutputSettings.getEntriesToJar();
      description =
          description
              + "; "
              + String.format(
                  "jar %s %s %s %s",
                  manifestFile.isPresent() ? "cfm" : "cf",
                  directToJarOutputSettings.getDirectToJarOutputPath(),
                  manifestFile.isPresent() ? manifestFile.get() : "",
                  Joiner.on(' ').join(entriesToJar));
    }

    return description;
  }

  @Override
  public String getShortName() {
    String name;
    if (abiJar != null) {
      name = "calculate_abi_from_source";
    } else if (directToJarOutputSettings.isPresent()) {
      name = "javac_jar";
    } else {
      name = getJavac().getShortName();
    }

    if (javac instanceof OutOfProcessJsr199Javac) {
      name += "(oop)";
    }

    return name;
  }

  /**
   * Returns a list of command-line options to pass to javac. These options reflect the
   * configuration of this javac command.
   *
   * @param context the ExecutionContext with in which javac will run
   * @return list of String command-line options.
   */
  @VisibleForTesting
  ImmutableList<String> getOptions(
      ExecutionContext context, ImmutableSortedSet<Path> buildClasspathEntries) {
    return getOptions(
        javacOptions,
        filesystem,
        resolver,
        outputDirectory,
        generatedCodeDirectory,
        context,
        buildClasspathEntries);
  }

  public static ImmutableList<String> getOptions(
      JavacOptions javacOptions,
      ProjectFilesystem filesystem,
      SourcePathResolver pathResolver,
      Path outputDirectory,
      Optional<Path> generatedCodeDirectory,
      ExecutionContext context,
      ImmutableSortedSet<Path> buildClasspathEntries) {
    final ImmutableList.Builder<String> builder = ImmutableList.builder();

    javacOptions.appendOptionsTo(
        new OptionsConsumer() {
          @Override
          public void addOptionValue(String option, String value) {
            builder.add("-" + option).add(value);
          }

          @Override
          public void addFlag(String flagName) {
            builder.add("-" + flagName);
          }

          @Override
          public void addExtras(Collection<String> extras) {
            builder.addAll(extras);
          }
        },
        pathResolver,
        filesystem);

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("-verbose");
    }

    // Specify the output directory.
    builder.add("-d").add(filesystem.resolve(outputDirectory).toString());

    if (generatedCodeDirectory.isPresent()) {
      builder.add("-s").add(filesystem.resolve(generatedCodeDirectory.get()).toString());
    }

    // Build up and set the classpath.
    if (!buildClasspathEntries.isEmpty()) {
      String classpath = Joiner.on(File.pathSeparator).join(buildClasspathEntries);
      builder.add("-classpath", classpath);
    } else {
      builder.add("-classpath", "''");
    }

    return builder.build();
  }

  /** @return The classpath entries used to invoke javac. */
  @VisibleForTesting
  ImmutableSortedSet<Path> getClasspathEntries() {
    return declaredClasspathEntries;
  }

  @VisibleForTesting
  ImmutableSortedSet<Path> getSrcs() {
    return javaSourceFilePaths;
  }
}
