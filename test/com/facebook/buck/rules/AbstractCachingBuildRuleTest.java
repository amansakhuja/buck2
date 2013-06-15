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

package com.facebook.buck.rules;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;

import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * Ensuring that build rule caching works correctly in Buck is imperative for both its performance
 * and correctness.
 */
public class AbstractCachingBuildRuleTest extends EasyMockSupport {

  private static final BuildTarget buildTarget = BuildTargetFactory.newInstance(
      "//src/com/facebook/orca", "orca");

  /**
   * Tests what should happen when a rule is built for the first time: it should have no cached
   * RuleKey, nor should it have any artifact in the ArtifactCache. The sequence of events should be
   * as follows:
   * <ol>
   *   <li>The rule invokes the {@link BuildRule#build(BuildContext)} method of each of its deps.
   *   <li>The rule computes its own {@link RuleKey}.
   *   <li>It compares its {@link RuleKey} to the one on disk, if present.
   *   <li>Because the rule has no {@link RuleKey} on disk, the rule tries to build itself.
   *   <li>First, it checks the artifact cache, but there is a cache miss.
   *   <li>The rule generates its build steps and executes them.
   *   <li>Upon executing its steps successfully, it should write its {@RuleKey} to disk.
   *   <li>It should persist its output to the ArtifactCache.
   * </ol>
   */
  @Test
  public void testBuildRuleWithoutSuccessFileOrCachedArtifact()
      throws IOException, InterruptedException, ExecutionException, StepFailedException {
    // Create a dep for the build rule.
    BuildRule dep = createMock(BuildRule.class);
    expect(dep.isVisibleTo(buildTarget)).andReturn(true);

    // Verify that there are no calls made to the visibility patterns.
    @SuppressWarnings("unchecked")
    ImmutableSet<BuildTargetPattern> visibilityPatterns = createMock(ImmutableSet.class);

    // Create an ArtifactCache whose expectations will be set later.
    ArtifactCache artifactCache = createMock(ArtifactCache.class);

    // Replay the mocks to instantiate the AbstractCachingBuildRule.
    replayAll();
    File output = new File("some_file");
    List<Step> buildSteps = Lists.newArrayList();
    AbstractCachingBuildRule cachingRule = createRule(
        ImmutableSet.of(dep),
        visibilityPatterns,
        ImmutableList.<InputRule>of(new InputRule("/dev/null") {
          @Override
          public RuleKey getRuleKey() {
            return new RuleKey("ae8c0f860a0ecad94ecede79b69460434eddbfbc", this);
          }
        }),
        buildSteps,
        /* ruleKeyOnDisk */ Optional.<RuleKey>absent(),
        output);
    verifyAll();
    resetAll();

    String expectedRuleKeyHash = Hashing.sha1().newHasher()
        .putBytes(RuleKey.Builder.buckVersionUID.getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("name".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes(cachingRule.getFullyQualifiedName().getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("buck.type".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("java_library".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("deps".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("19d2558a6bd3a34fb3f95412de9da27ed32fe208".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putByte(RuleKey.Builder.SEPARATOR)

        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("buck.inputs".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putBytes("ae8c0f860a0ecad94ecede79b69460434eddbfbc".getBytes())
        .putByte(RuleKey.Builder.SEPARATOR)
        .putByte(RuleKey.Builder.SEPARATOR)

        .hash()
        .toString();

    // The EventBus should be updated with events indicating how the rule was built.
    EventBus eventBus = createMock(EventBus.class);
    eventBus.post(BuildEvents.started(cachingRule));
    eventBus.post(BuildEvents.finished(cachingRule, BuildRuleStatus.SUCCESS, CacheResult.MISS));

    // The BuildContext that will be used by the rule's build() method.
    BuildContext context = createMock(BuildContext.class);
    expect(context.getExecutor()).andReturn(MoreExecutors.sameThreadExecutor());
    expect(context.getEventBus()).andReturn(eventBus).times(2);
    StepRunner stepRunner = createMock(StepRunner.class);
    expect(context.getCommandRunner()).andReturn(stepRunner);
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(context.getProjectFilesystem()).andReturn(projectFilesystem).times(2);
    String pathToSuccessFile = cachingRule.getPathToSuccessFile();
    projectFilesystem.createParentDirs(new File(pathToSuccessFile));
    Capture<Iterable<String>> linesCapture = new Capture<Iterable<String>>();
    projectFilesystem.writeLinesToPath(capture(linesCapture), eq(pathToSuccessFile));

    // There will initially be a cache miss, later followed by a cache store.
    RuleKey expectedRuleKey = new RuleKey(expectedRuleKeyHash, cachingRule);
    expect(artifactCache.fetch(expectedRuleKey, output)).andReturn(false);
    artifactCache.store(expectedRuleKey, output);
    expect(context.getArtifactCache()).andReturn(artifactCache).times(2);

    // The dependent rule will be built immediately with a distinct rule key.
    expect(dep.build(context)).andReturn(
        Futures.immediateFuture(new BuildRuleSuccess(dep, BuildRuleSuccess.Type.BUILT_LOCALLY)));
    expect(dep.getRuleKey())
        .andReturn(new RuleKey("19d2558a6bd3a34fb3f95412de9da27ed32fe208", dep));

    // Add a build step so we can verify that the steps are executed.
    Step buildStep = createMock(Step.class);
    buildSteps.add(buildStep);
    stepRunner.runStepForBuildTarget(buildStep, buildTarget);

    // Attempting to build the rule should force a rebuild due to a cache miss.
    replayAll();
    BuildRuleSuccess result = cachingRule.build(context).get();
    assertEquals(BuildRuleSuccess.Type.BUILT_LOCALLY, result.getType());
    verifyAll();

    // Verify that the correct value was written to the .success file.
    String firstLineInSuccessFile = Iterables.getFirst(linesCapture.getValue(),
        /* defaultValue */ null);
    assertEquals(expectedRuleKeyHash, firstLineInSuccessFile);
  }


  // TODO(mbolin): Test that when the success files match, nothing is built and nothing is written
  // back to the cache.

  // TODO(mbolin): Test that when the value in the success file does not agree with the current
  // value, the rule is rebuilt and the result is written back to the cache.

  // TODO(mbolin): Test that if there is a cache hit, nothing is built and nothing is written back
  // to the cache.

  // TODO(mbolin): Test that a failure when executing the build steps is propagated appropriately.

  // TODO(mbolin): Test what happens when the cache's methods throw an exception.

  private static AbstractCachingBuildRule createRule(
      ImmutableSet<BuildRule> deps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns,
      final Iterable<InputRule> inputRules,
      final List<Step> buildSteps,
      final Optional<RuleKey> ruleKeyOnDisk,
      @Nullable final File output) {
    Comparator<BuildRule> comparator = RetainOrderComparator.createComparator(deps);
    ImmutableSortedSet<BuildRule> sortedDeps = ImmutableSortedSet.copyOf(comparator, deps);

    BuildRuleParams buildRuleParams = new BuildRuleParams(buildTarget,
        sortedDeps,
        visibilityPatterns);
    return new AbstractCachingBuildRule(buildRuleParams) {

      private Iterable<InputRule> inputs = inputRules;

      @Override
      public BuildRuleType getType() {
        return BuildRuleType.JAVA_LIBRARY;
      }

      @Override
      public Iterable<InputRule> getInputs() {
        return inputs;
      }

      @Override
      public File getOutput() {
        return output;
      }

      @Override
      Optional<RuleKey> getRuleKeyOnDisk(ProjectFilesystem projectFilesystem) {
        return ruleKeyOnDisk;
      }

      @Override
      protected List<Step> buildInternal(BuildContext context) throws IOException {
        return buildSteps;
      }

      @Override
      protected Iterable<String> getInputsToCompareToOutput(BuildContext context) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
