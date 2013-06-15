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
package com.facebook.buck.android;

import com.facebook.buck.step.CompositeStep;
import com.facebook.buck.step.DefaultStepRunner;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.fs.RepackZipEntriesStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.step.fs.XzStep;
import com.facebook.buck.step.fs.ZipStep;
import com.facebook.buck.util.ClasspathTraversal;
import com.facebook.buck.util.ClasspathTraverser;
import com.facebook.buck.util.DefaultClasspathTraverser;
import com.facebook.buck.util.FileLike;
import com.facebook.buck.util.MoreFiles;
import com.facebook.buck.util.Paths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

/**
 * Optimized dx command runner which can invoke multiple dx commands in parallel and also avoid
 * doing unnecessary dx invocations in the first place.
 * <p>
 * This is most appropriately represented as a build rule itself (which depends on individual dex
 * rules) however this would require significant refactoring of AndroidBinaryRule that would be
 * disruptive to other initiatives in flight (namely, ApkBuilder).  It is also debatable that it is
 * even the right course of action given that it would require dynamically modifying the DAG.
 */
public class SmartDexingStep implements Step {
  private final InputResolver inputResolver;
  private final String successDir;
  private final Optional<Integer> numThreads;
  private final DexStore dexStore;
  private ListeningExecutorService dxExecutor;

  /** Lazily initialized.  See {@link InputResolver#createOutputToInputs()}. */
  private Multimap<File, File> outputToInputs;

  /**
   * @param primaryOutputPath Path for the primary dex artifact.
   * @param primaryInputsToDex Set of paths to include as inputs for the primary dex artifact.
   * @param secondaryOutputDir Directory path for the secondary dex artifacts, if there are any.
   *     Note that this directory will be pruned such that only those secondary outputs generated
   *     by this command will remain in the directory!
   * @param secondaryInputsDir Directory path containing input jar files to use as dx input.
   *     Note that for each file in this directory, a separate dx invocation will be started with
   *     that file as input.  Do not pass a directory that contains non-dexable artifacts!
   * @param successDir Directory where success artifacts are written.
   * @param numThreads Number of threads to use when invoking dx commands.  If absent, a
   *     reasonable default will be selected based on the number of available processors.
   * @param dexStore Specify the way secondary dexes are to be stored in the APK (e.g.
   *     within jar files, or as xz-compressed files).
   */
  public SmartDexingStep(
      String primaryOutputPath,
      Set<String> primaryInputsToDex,
      Optional<String> secondaryOutputDir,
      Optional<String> secondaryInputsDir,
      String successDir,
      Optional<Integer> numThreads,
      DexStore dexStore) {
    this.inputResolver = new InputResolver(primaryOutputPath,
        primaryInputsToDex,
        secondaryOutputDir,
        secondaryInputsDir);
    this.successDir = Preconditions.checkNotNull(successDir);
    this.numThreads = Preconditions.checkNotNull(numThreads);
    this.dexStore = Preconditions.checkNotNull(dexStore);
  }

  @VisibleForTesting
  protected ListeningExecutorService createDxExecutor() {
    int numThreadsValue;
    if (numThreads.isPresent()) {
      Preconditions.checkArgument(numThreads.get() >= 1,
          "Must specify at least 1 thread on which to run dx");
      numThreadsValue = numThreads.get();
    } else {
      numThreadsValue = determineOptimalThreadCount();
    }
    return MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(numThreadsValue));
  }

  private final ListeningExecutorService getDxExecutor() {
    if (dxExecutor == null) {
      dxExecutor = createDxExecutor();
    }
    return dxExecutor;
  }

  private static int determineOptimalThreadCount() {
    return (int)(1.25 * Runtime.getRuntime().availableProcessors());
  }

  private final Multimap<File, File> getOutputToInputsMultimap() {
    if (outputToInputs == null) {
      outputToInputs = inputResolver.createOutputToInputs(dexStore);
    }
    return outputToInputs;
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      Multimap<File, File> outputToInputs = getOutputToInputsMultimap();
      runDxCommands(context, outputToInputs);
      if (inputResolver.hasSecondaryOutput()) {
        removeExtraneousSecondaryArtifacts(
            inputResolver.getSecondaryOutputDir(),
            outputToInputs.keySet(),
            context);
      }
      return 0;
    } catch (StepFailedException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
  }

  private void runDxCommands(ExecutionContext context, Multimap<File, File> outputToInputs)
      throws StepFailedException, IOException {
    DefaultStepRunner commandRunner = new DefaultStepRunner(context, getDxExecutor());

    // Invoke dx commands in parallel for maximum thread utilization.  In testing, dx revealed
    // itself to be CPU (and not I/O) bound making it a good candidate for parallelization.
    List<Step> dxSteps = generateDxCommands(context, outputToInputs);
    commandRunner.runCommandsInParallelAndWait(dxSteps);
  }

  /**
   * Prune the secondary output directory of any files that we didn't generate.  This is
   * needed because we crudely add all files in this directory to the final APK, but the number
   * may have been reduced due to split-zip having less code to process.
   * <p>
   * This is also a defensive measure to cleanup extraneous artifacts left behind due to
   * changes to buck itself.
   */
  private void removeExtraneousSecondaryArtifacts(
      File secondaryOutputDir,
      Set<File> producedArtifacts,
      ExecutionContext context) throws IOException {
    for (File secondaryOutput : secondaryOutputDir.listFiles()) {
      if (!producedArtifacts.contains(secondaryOutput)) {
        MoreFiles.rmdir(secondaryOutput.getAbsolutePath(), context.getProcessExecutor());
      }
    }
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "smart-dex";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder b = new StringBuilder();
    b.append(getShortName(context));
    b.append(' ');

    Multimap<File, File> outputToInputs = getOutputToInputsMultimap();
    for (File output : outputToInputs.keySet()) {
      b.append("-out ");
      b.append(output.getPath());
      b.append("-in ");
      Joiner.on(':').appendTo(b, outputToInputs.get(output));
    }

    return b.toString();
  }

  /**
   * Once the {@code .class} files have been split into separate zip files, each must be converted
   * to a {@code .dex} file.
   */
  private List<Step> generateDxCommands(
      ExecutionContext context,
      Multimap<File, File> outputToInputs) throws IOException {
    ImmutableList.Builder<DxPseudoRule> pseudoRules = ImmutableList.builder();

    for (File outputFile : outputToInputs.keySet()) {
      // This is silly to do so much conversion from String => File and back again but it is
      // sort of a necessary evil since we're internally simulating a bridge between Java and
      // the outside world (commands generally seen as external, and rules are generated by parsing
      // JSON input).
      pseudoRules.add(new DxPseudoRule(context,
          ImmutableSet.copyOf(Paths.transformFileToAbsolutePath(outputToInputs.get(outputFile))),
          outputFile.getPath(),
          new File(successDir, outputFile.getName()).getPath()));
    }

    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    for (DxPseudoRule pseudoRule : pseudoRules.build()) {
      if (!pseudoRule.checkIsCached()) {
        commands.addAll(pseudoRule.buildInternal());
      }
    }

    return commands.build();
  }

  // This is a terrible shared kludge between SmartDexingCommand and SplitZipCommand.
  // SplitZipCommand writes the metadata.txt file assuming this will be the final filename
  // in the APK...
  public static String transformInputToDexOutput(String filename, DexStore dexStore) {
    if (DexStore.XZ == dexStore) {
      return Paths.getBasename(filename, ".jar") + ".dex.jar.xz";
    } else {
      return Paths.getBasename(filename, ".jar") + ".dex.jar";
    }
  }

  // Helper class to break down the complex set of paths that this command accepts.
  @VisibleForTesting
  static class InputResolver {
    private final String primaryOutputPath;
    private final Set<String> primaryInputsToDex;
    private final Optional<String> secondaryOutputDir;
    private final Optional<String> secondaryInputsDir;

    public InputResolver(
        String primaryOutputPath,
        Set<String> primaryInputsToDex,
        Optional<String> secondaryOutputDir,
        Optional<String> secondaryInputsDir) {
      this.primaryOutputPath = Preconditions.checkNotNull(primaryOutputPath);
      this.primaryInputsToDex = ImmutableSet.copyOf(primaryInputsToDex);
      Preconditions.checkArgument(!(secondaryOutputDir.isPresent() ^ secondaryInputsDir.isPresent()),
          "Secondary input and output must be passed together (either both absent or both present)");
      this.secondaryOutputDir = secondaryOutputDir;
      this.secondaryInputsDir = secondaryInputsDir;
    }

    /*
     * Create a multimap whose keys are output files and whose values are inputs passed to the dx
     * command.  This defines a set of rules where the keySet of the returned multimap is the
     * set of expected files to exist after smart dexing completes.
     */
    public Multimap<File, File> createOutputToInputs(DexStore dexStore) {
      final ImmutableMultimap.Builder<File, File> map = ImmutableMultimap.builder();

      // Add the primary output.
      File primaryOutputFile = new File(primaryOutputPath);
      for (String primaryInputToDex : primaryInputsToDex) {
        map.put(primaryOutputFile, new File(primaryInputToDex));
      }

      // Add all secondary outputs (one for each file in the secondary inputs dir).
      if (secondaryInputsDir.isPresent()) {
        File secondaryOutputDirFile = new File(secondaryOutputDir.get());
        File secondaryInputsDirFile = new File(secondaryInputsDir.get());
        for (File secondaryInputFile : secondaryInputsDirFile.listFiles()) {
          // May be either directories or jar files, doesn't matter.
          File secondaryOutputFile = new File(secondaryOutputDirFile,
              transformInputToDexOutput(secondaryInputFile.getName(), dexStore));
          map.put(secondaryOutputFile, secondaryInputFile);
        }
      }

      return map.build();
    }

    public boolean hasSecondaryOutput() {
      return secondaryOutputDir.isPresent();
    }

    public File getSecondaryOutputDir() {
      return new File(secondaryOutputDir.get());
    }
  }

  /**
   * Internally designed to simulate a dexing buck rule so that once refactored more broadly as
   * such it should be straightforward to convert this code.
   * <p>
   * This pseudo rule does not use the normal .success file model but instead checksums its
   * inputs.  This is because the input zip files are guaranteed to have changed on the
   * filesystem (ZipSplitter will always write them out even if the same), but the contents
   * contained in the zip may not have changed.
   */
  @VisibleForTesting
  static class DxPseudoRule {
    private final ExecutionContext context;
    private final Set<String> srcs;
    private final String outputPath;
    private final String outputHashPath;
    private String newInputsHash;

    public DxPseudoRule(ExecutionContext context,
        Set<String> srcs,
        String outputPath,
        String outputHashPath) {
      this.context = Preconditions.checkNotNull(context);
      this.srcs = ImmutableSet.copyOf(srcs);
      this.outputPath = Preconditions.checkNotNull(outputPath);
      this.outputHashPath = Preconditions.checkNotNull(outputHashPath);
    }

    /**
     * Read the previous run's hash from the filesystem.
     *
     * @return Previous hash if there was one; null otherwise.
     */
    @Nullable
    private String getPreviousInputsHash() {
      File outputHashFile = new File(outputHashPath);
      if (outputHashFile.exists()) {
        try {
          return Iterables.getFirst(
              Files.readLines(outputHashFile, Charsets.UTF_8),
              null);
        } catch (IOException e) {
          context.getStdErr().println(context.getAnsi().asWarningText(
              String.format("Error reading success file: %s", outputHashPath)));
          // Fall through, this is not fatal...
        }
      }
      // This will trigger the dx command to run again.
      return null;
    }

    @VisibleForTesting
    String hashInputs() throws IOException {
      final Hasher hasher = Hashing.sha1().newHasher();

      // Hash all inputs in both srcs and entry order (which is very crudely expected to be stable
      // across invocations).  If it's not stable, all that means is that we'll run more dx commands
      // than was necessary.  Note that it is not possible to simply hash the inputs themselves
      // for two reasons: 1) they may one day be directories, 2) zip files may contain the same
      // entry contents but change on disk due to entry metadata.
      ClasspathTraverser traverser = new DefaultClasspathTraverser();
      try {
        traverser.traverse(new ClasspathTraversal(Paths.transformPathToFile(srcs)) {
          @Override
          public void visit(FileLike fileLike) {
            try {
              hasher.putBytes(fileLike.fastHash().asBytes());
            } catch (IOException e) {
              // Pass it along...
              throw new RuntimeException(e);
            }
          }
        });
      } catch (RuntimeException e) {
        Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
        throw Throwables.propagate(e);
      }

      return hasher.hash().toString();
    }

    public boolean checkIsCached() throws IOException {
      newInputsHash = hashInputs();

      // Make sure the output dex file isn't newer than the output hash file.
      long outputHashFileModTime = new File(outputHashPath).lastModified();
      long outputFileModTime = new File(outputPath).lastModified();
      if (outputFileModTime > outputHashFileModTime) {
        return false;
      }

      // Verify input hashes.
      String currentInputsHash = getPreviousInputsHash();
      return newInputsHash.equals(currentInputsHash);
    }

    /**
     * Returns true if the output of dexing should be saved as .dex.jar. This is based on the
     * target file extension, much as {@code dx} itself chooses whether to embed the dex inside
     * a jar/zip based on the destination file passed to it.
     */
    private boolean useXzCompression() {
      return outputPath.endsWith(".dex.jar.xz");
    }

    public List<Step> buildInternal() {
      Preconditions.checkState(newInputsHash != null, "Must call checkIsCached first!");

      List<Step> steps = Lists.newArrayList();
      if (useXzCompression()) {
        String tempDexJarOutput = outputPath.replaceAll("\\.jar\\.xz$", ".tmp.jar");
        steps.add(new DxStep(tempDexJarOutput, srcs));
        // We need to make sure classes.dex is STOREd in the .dex.jar file, otherwise .XZ
        // compression won't be effective.
        String repackedJar = outputPath.replaceAll("\\.xz$", "");
        steps.add(new RepackZipEntriesStep(
            tempDexJarOutput,
            repackedJar,
            ImmutableSet.of("classes.dex"),
            ZipStep.MIN_COMPRESSION_LEVEL,
            /* workingDirectory */ null
        ));
        steps.add(new XzStep(repackedJar));
      } else {
        steps.add(new DxStep(outputPath, srcs));
      }
      steps.add(new WriteFileStep(newInputsHash, outputHashPath));

      // Use a composite step to ensure that runDxSteps can still make use of
      // runStepsInParallelAndWait.  This is necessary to keep the DxStep and
      // WriteFileStep dependent in series.
      return ImmutableList.<Step>of(new CompositeStep(steps));
    }
  }
}
