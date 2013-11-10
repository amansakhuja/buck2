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

package com.facebook.buck.event.listener;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ChromeTraceEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.ArtifactCacheConnectEvent;
import com.facebook.buck.rules.ArtifactCacheEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.FakeStep;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.EventBus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class ChromeTraceBuildListenerTest {
  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testDeleteFiles() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot());

    String tracePath = String.format("%s/build.trace", BuckConstant.BUCK_TRACE_DIR);
    File traceFile = new File(tmpDir.getRoot(), tracePath);
    projectFilesystem.createParentDirs(tracePath);
    traceFile.createNewFile();
    traceFile.setLastModified(0);

    for (int i = 0; i < 10; ++i) {
      File oldResult = new File(tmpDir.getRoot(),
          String.format("%s/build.100%d.trace", BuckConstant.BUCK_TRACE_DIR, i));
      oldResult.createNewFile();
      oldResult.setLastModified(TimeUnit.SECONDS.toMillis(i));
    }

    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(projectFilesystem,
        new IncrementingFakeClock(),
        3);

    listener.deleteOldTraces();

    ImmutableList<String> files = FluentIterable.
        from(Arrays.asList(projectFilesystem.listFiles(BuckConstant.BUCK_TRACE_DIR))).
        transform(new Function<File, String>() {
          @Override
          public String apply(File input) {
            return input.getName();
          }
        }).toList();
    assertEquals(4, files.size());
    assertEquals(ImmutableSortedSet.of("build.trace",
                                       "build.1009.trace",
                                       "build.1008.trace",
                                       "build.1007.trace"),
        ImmutableSortedSet.copyOf(files));
  }

  @Test
  public void testBuildJson() throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(tmpDir.getRoot());

    ChromeTraceBuildListener listener = new ChromeTraceBuildListener(projectFilesystem,
        new IncrementingFakeClock(),
        42);

    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");

    BuildRule rule = new FakeBuildRule(
        new BuildRuleType("fakeRule"),
        target,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of());
    FakeStep step = new FakeStep("fakeStep", "I'm a Fake Step!", 0);

    ExecutionContext context = createMock(ExecutionContext.class);
    replay(context);

    ImmutableList<BuildTarget> buildTargets = ImmutableList.of(target);
    Clock fakeClock = new IncrementingFakeClock(TimeUnit.MILLISECONDS.toNanos(1));
    BuckEventBus eventBus = BuckEventBusFactory.newInstance(fakeClock,
        "ChromeTraceBuildListenerTestBuildId");
    Supplier<Long> threadIdSupplier = BuckEventBusFactory.getThreadIdSupplierFor(eventBus);
    EventBus rawEventBus = BuckEventBusFactory.getEventBusFor(eventBus);
    eventBus.register(listener);

    eventBus.post(CommandEvent.started("party", true));
    eventBus.post(ArtifactCacheConnectEvent.started());
    eventBus.post(ArtifactCacheConnectEvent.finished());
    eventBus.post(BuildEvent.started(buildTargets));
    eventBus.post(ArtifactCacheEvent.started(ArtifactCacheEvent.Operation.FETCH,
        new RuleKey("abc123")));
    eventBus.post(ArtifactCacheEvent.finished(ArtifactCacheEvent.Operation.FETCH,
        new RuleKey("abc123"),
        CacheResult.CASSANDRA_HIT));
    eventBus.post(BuildRuleEvent.started(rule));
    eventBus.post(StepEvent.started(step, "I'm a Fake Step!"));

    // Intentionally fire events out of order to verify sorting happens.
    BuckEvent stepFinished = StepEvent.finished(step, "I'm a Fake Step!", 0);
    stepFinished.configure(fakeClock.currentTimeMillis(),
        fakeClock.nanoTime(),
        threadIdSupplier.get(),
        eventBus.getBuildId());


    BuckEvent ruleFinished = BuildRuleEvent.finished(
        rule,
        BuildRuleStatus.SUCCESS,
        CacheResult.MISS,
        Optional.of(BuildRuleSuccess.Type.BUILT_LOCALLY));
    ruleFinished.configure(fakeClock.currentTimeMillis(),
        fakeClock.nanoTime(),
        threadIdSupplier.get(),
        eventBus.getBuildId());

    rawEventBus.post(ruleFinished);
    rawEventBus.post(stepFinished);

    eventBus.post(BuildEvent.finished(buildTargets, 0));
    eventBus.post(CommandEvent.finished("party", true, 0));
    listener.outputTrace();

    File resultFile = new File(tmpDir.getRoot(), BuckConstant.BUCK_TRACE_DIR + "/build.trace");

    ObjectMapper mapper = new ObjectMapper();

    List<ChromeTraceEvent> resultMap = mapper.readValue(
        resultFile,
        new TypeReference<List<ChromeTraceEvent>>() {});

    assertEquals(12, resultMap.size());
    assertEquals("party", resultMap.get(0).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(0).getPhase());
    assertEquals("artifact_connect", resultMap.get(1).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(1).getPhase());
    assertEquals("artifact_connect", resultMap.get(2).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(2).getPhase());
    assertEquals("build", resultMap.get(3).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(3).getPhase());
    assertEquals("artifact_fetch", resultMap.get(4).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(4).getPhase());
    assertEquals("artifact_fetch", resultMap.get(5).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(5).getPhase());
    assertEquals("//fake:rule", resultMap.get(6).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(6).getPhase());
    assertEquals("fakeStep", resultMap.get(7).getName());
    assertEquals(ChromeTraceEvent.Phase.BEGIN, resultMap.get(7).getPhase());
    assertEquals("fakeStep", resultMap.get(8).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(8).getPhase());
    assertEquals("//fake:rule", resultMap.get(9).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(9).getPhase());
    assertEquals("build", resultMap.get(10).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(10).getPhase());
    assertEquals("party", resultMap.get(11).getName());
    assertEquals(ChromeTraceEvent.Phase.END, resultMap.get(11).getPhase());

    verify(context);
  }
}
